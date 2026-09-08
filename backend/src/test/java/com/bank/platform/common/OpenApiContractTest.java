package com.bank.platform.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Fast, local version of the CI contract gate: fetch /v3/api-docs from the
 * running application context (H2 test profile) and compare it semantically
 * with the committed frontend/openapi.json. Jackson's JsonNode equality is
 * order-insensitive for object keys, mirroring the canonical comparison CI
 * does against a real jar on PostgreSQL.
 *
 * To regenerate the committed contract after an API change:
 *   backend: ./mvnw test -Dopenapi.regen=true -Dtest=OpenApiContractTest
 *   frontend: npm run openapi   (regenerates lib/api-gen.ts from openapi.json)
 * Re-run without the flag to verify the committed file matches the code.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiContractTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;

  private static final Path COMMITTED = Path.of("..", "frontend", "openapi.json").normalize();

  @Test
  void contractMatchesCommittedSpec() throws Exception {
    String generated = mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();

    JsonNode generatedNode = objectMapper.readTree(generated);
    if (Boolean.getBoolean("openapi.regen")) {
      // springdoc emits object keys in JVM-dependent order, so a raw write
      // churns the committed file on every regen. Sort keys canonically
      // (mirroring the CI comparison) so a regen diff is a real change only.
      Files.writeString(COMMITTED,
          objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(canonical(generatedNode)));
      return;
    }

    JsonNode expected = objectMapper.readTree(readCommitted());
    // springdoc derives the server URL from the incoming request, so a
    // MockMvc context has no port while the CI jar answers on :8080. The
    // committed spec keeps the CI value; ignore the field for this compare.
    if (!withoutServers(expected).equals(withoutServers(generatedNode))) {
      throw new AssertionError(
          "OpenAPI contract drifted from the backend. Regenerate it: run\n"
              + "  backend:  ./mvnw test -Dopenapi.regen=true -Dtest=OpenApiContractTest\n"
              + "  frontend: npm run openapi");
    }
  }

  /**
   * Semantic surface: the spec must NAME reality, not just exist. Login
   * lists both success outcomes with their real codes (200 session, 202 MFA
   * challenge), errors reference the single ApiProblem schema, response
   * schemas carry required lists with genuine nullability, enums enumerate
   * their values, and the one public route carries no bearer requirement.
   */
  @Test
  void contractNamesReality() throws Exception {
    JsonNode doc = objectMapper.readTree(mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString());
    JsonNode schemas = doc.at("/components/schemas");

    // Login: two explicit outcomes, actual status codes, Problem errors.
    JsonNode login = doc.at("/paths/~1api~1v1~1auth~1login/post/responses");
    assertEquals("#/components/schemas/AuthResponse",
        login.at("/200/content/application~1json/schema/$ref").asText());
    assertEquals("#/components/schemas/MfaRequiredResponse",
        login.at("/202/content/application~1json/schema/$ref").asText());
    assertEquals("#/components/schemas/ApiProblem",
        login.at("/429/content/application~1json/schema/$ref").asText());

    // ApiProblem is one consistent error schema with required fields.
    assertTrue(schemas.at("/ApiProblem/required").isArray());

    // Response schemas carry required lists; nullable fields stay nullable.
    JsonNode tx = schemas.at("/TransactionResponse");
    assertTrue(requiredContains(tx, "id"));
    assertTrue(requiredContains(tx, "status"));
    assertTrue(tx.at("/properties/postedAt/type").toString().contains("null"),
        "null-capable fields stay nullable");

    // Enums enumerate the legal values instead of open strings.
    assertTrue(tx.at("/properties/status/enum").toString().contains("POSTED"));
    assertTrue(schemas.at("/UserResponse/properties/role/enum").isArray());

    // The cursor envelope is in the contract with its three fields.
    JsonNode page = schemas.at("/TransactionHistoryPage");
    assertTrue(requiredContains(page, "items"));
    assertTrue(requiredContains(page, "nextCursor"));

    // Public route: explicitly no bearer security.
    assertTrue(doc.at("/paths/~1api~1public~1stats/get/security").isArray());
    assertTrue(doc.at("/paths/~1api~1public~1stats/get/security").isEmpty());
  }

  private static boolean requiredContains(JsonNode schema, String field) {
    JsonNode required = schema.get("required");
    if (required == null || !required.isArray()) {
      return false;
    }
    for (JsonNode item : required) {
      if (field.equals(item.asText())) {
        return true;
      }
    }
    return false;
  }

  private static JsonNode withoutServers(JsonNode root) {
    if (!root.isObject()) {
      return root;
    }
    tools.jackson.databind.node.ObjectNode copy =
        (tools.jackson.databind.node.ObjectNode) root.deepCopy();
    copy.remove("servers");
    return canonical(copy);
  }

  /** Recursively sorts object keys so comparisons and regenerations never care about map order. */
  private static JsonNode canonical(JsonNode node) {
    if (node.isObject()) {
      tools.jackson.databind.node.ObjectNode sorted =
          tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
      node.properties().stream()
          .sorted(java.util.Map.Entry.comparingByKey())
          .forEach(e -> sorted.set(e.getKey(), canonical(e.getValue())));
      return sorted;
    }
    if (node.isArray()) {
      tools.jackson.databind.node.ArrayNode array =
          tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
      node.forEach(item -> array.add(canonical(item)));
      return array;
    }
    return node.deepCopy();
  }

  private String readCommitted() throws IOException {
    if (!Files.exists(COMMITTED)) {
      throw new IOException("Committed contract not found at " + COMMITTED.toAbsolutePath());
    }
    return Files.readString(COMMITTED);
  }
}
