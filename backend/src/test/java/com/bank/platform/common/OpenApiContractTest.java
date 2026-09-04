package com.bank.platform.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
      Files.writeString(COMMITTED,
          objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(generatedNode));
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

  private static JsonNode withoutServers(JsonNode root) {
    if (!root.isObject()) {
      return root;
    }
    com.fasterxml.jackson.databind.node.ObjectNode copy = root.deepCopy();
    copy.remove("servers");
    return copy;
  }

  private String readCommitted() throws IOException {
    if (!Files.exists(COMMITTED)) {
      throw new IOException("Committed contract not found at " + COMMITTED.toAbsolutePath());
    }
    return Files.readString(COMMITTED);
  }
}
