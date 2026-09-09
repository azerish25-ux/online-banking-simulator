package com.bank.platform.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

  private final String version;
  private final String serverUrl;

  public OpenApiConfig(
      @Value("${info.app.version:1.3.0}") String version,
      @Value("${app.api.server-url:http://localhost:8080}") String serverUrl) {
    this.version = version;
    this.serverUrl = serverUrl;
  }

  @Bean
  public OpenAPI bankApi() {
    return new OpenAPI()
        .info(new Info()
            .title(Brand.NAME + " API")
            // One version source: application.yml's info.app.version, which the
            // health endpoint reports: a pom bump can no longer drift the two.
            .version(version)
            .description("Full-stack online banking demo. Errors are RFC-7807 "
                + "(ApiProblem); money travels as JSON strings."))
        // Pin the servers list (app.api.server-url, default the documented
        // dev origin). Without it springdoc derives the URL from the incoming
        // request: a MockMvc-regenerated contract says "http://localhost"
        // while a live backend on :8080 emits "http://localhost:8080", so the
        // same spec drifted between the two generation paths.
        .servers(List.of(new Server().url(serverUrl)))
        .addSecurityItem(new SecurityRequirement().addList("bearer"))
        .components(new Components().addSecuritySchemes("bearer",
            new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")));
  }

  /**
   * Contract repairs that no single annotation can express:
   *
   * <ol>
   *   <li><b>Required fields</b>: springdoc derives schema property lists
   *       from records but marks every property optional because the DTOs
   *       carry no per-field validation. The wire always sends every field of
   *       every RESPONSE record, so response-only schemas get an explicit
   *       {@code required} list: the generated types then describe reality
   *       without a blanket {@code Required<...>} repair on the client.
   *       Request schemas are left alone: which request fields are optional
   *       is real information.</li>
   *   <li><b>Public routes</b>: the global bearer requirement must not
   *       advertise authentication on the one anonymous endpoint
   *       (/api/public/**); its security is cleared so the contract says it
   *       is callable without a token.</li>
   * </ol>
   */
  @Bean
  public OpenApiCustomizer contractHonestyCustomizer() {
    return openApi -> {
      Components components = openApi.getComponents();
      if (components == null || components.getSchemas() == null) {
        return;
      }
      Map<String, Schema> schemas = components.getSchemas();

      Set<String> requestSchemas = new HashSet<>();
      Set<String> responseSchemas = new HashSet<>();
      for (Map.Entry<String, PathItem> entry : openApi.getPaths().entrySet()) {
        for (Operation operation : entry.getValue().readOperations()) {
          // Public endpoints need no bearer token; an explicit empty security
          // list opts the operation out of the global requirement so the spec
          // matches the security config.
          if (entry.getKey().startsWith("/api/public/")) {
            operation.setSecurity(new ArrayList<>());
          }
          if (operation.getRequestBody() != null
              && operation.getRequestBody().getContent() != null) {
            collect(operation.getRequestBody().getContent(), requestSchemas, schemas);
          }
          if (operation.getResponses() != null) {
            for (ApiResponse response : operation.getResponses().values()) {
              if (response.getContent() != null) {
                collect(response.getContent(), responseSchemas, schemas);
              }
            }
          }
        }
      }

      // Response-only schemas describe what the server ALWAYS sends: mark
      // every declared property required. Schemas that also appear in a
      // request keep their optionality (requests legitimately omit fields).
      responseSchemas.removeAll(requestSchemas);
      for (String name : responseSchemas) {
        Schema<?> schema = schemas.get(name);
        // springdoc leaves the top-level type unset on record schemas (it is
        // only inferred at serialization), so presence of properties is the
        // object test. Array/map schemas have no properties and stay as-is.
        if (schema != null && schema.getProperties() != null && !schema.getProperties().isEmpty()) {
          schema.setRequired(new ArrayList<>(schema.getProperties().keySet()));
        }
      }
    };
  }

  /** Walks every schema reference reachable from a content map (recursively). */
  private void collect(io.swagger.v3.oas.models.media.Content content,
      Set<String> names, Map<String, Schema> schemas) {
    content.values().forEach(media -> {
      if (media.getSchema() != null) {
        collectSchema(media.getSchema(), names, schemas, new HashSet<>());
      }
    });
  }

  private void collectSchema(Schema<?> schema, Set<String> names,
      Map<String, Schema> schemas, Set<String> seen) {
    if (schema == null) {
      return;
    }
    String ref = schema.get$ref();
    if (ref != null && ref.startsWith("#/components/schemas/")) {
      String name = ref.substring(ref.lastIndexOf('/') + 1);
      if (names.add(name) && seen.add(name)) {
        Schema<?> target = schemas.get(name);
        if (target != null) {
          collectSchema(target, names, schemas, seen);
        }
      }
      return;
    }
    if (schema.getProperties() != null) {
      schema.getProperties().values().forEach(p -> collectSchema(p, names, schemas, seen));
    }
    if (schema.getAllOf() != null) {
      schema.getAllOf().forEach(p -> collectSchema(p, names, schemas, seen));
    }
    if (schema.getAnyOf() != null) {
      schema.getAnyOf().forEach(p -> collectSchema(p, names, schemas, seen));
    }
    if (schema.getOneOf() != null) {
      schema.getOneOf().forEach(p -> collectSchema(p, names, schemas, seen));
    }
    if (schema.getItems() != null) {
      collectSchema(schema.getItems(), names, schemas, seen);
    }
  }
}
