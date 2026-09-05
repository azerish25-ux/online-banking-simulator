package com.bank.platform.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

  private final String version;
  private final String serverUrl;

  public OpenApiConfig(
      @Value("${info.app.version:1.2.0}") String version,
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
            // health endpoint reports - a pom bump can no longer drift the two.
            .version(version)
            .description("Full-stack online banking demo. Errors are RFC-7807; money travels as JSON strings."))
        // Pin the servers list. Without it springdoc derives the URL from the
        // incoming request, so a test-regenerated contract says
        // "http://localhost" while a live backend on :8080 emits
        // "http://localhost:8080" and the contract gate can never agree.
        .servers(List.of(new Server().url(serverUrl)))
        .addSecurityItem(new SecurityRequirement().addList("bearer"))
        .components(new Components().addSecuritySchemes("bearer",
            new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")));
  }
}
