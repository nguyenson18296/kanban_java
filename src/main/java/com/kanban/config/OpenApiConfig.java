package com.kanban.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger document (title/description/version + bearer auth) — same metadata as main.ts. */
@Configuration
public class OpenApiConfig {
  @Bean
  public OpenAPI kanbanOpenApi() {
    return new OpenAPI()
        .info(new Info().title("Kanban API").description("Kanban board backend API").version("1.0"))
        .components(new Components().addSecuritySchemes("bearer",
            new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
  }
}
