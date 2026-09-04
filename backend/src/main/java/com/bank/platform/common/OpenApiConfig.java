package com.bank.platform.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

  private final String version;

  public OpenApiConfig(@Value("${info.app.version:1.2.0}") String version) {
    this.version = version;
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
        .addSecurityItem(new SecurityRequirement().addList("bearer"))
        .components(new Components().addSecuritySchemes("bearer",
            new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")));
  }
}
