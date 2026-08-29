package com.kanban.config;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Swagger is mounted only when {@code NODE_ENV !== 'production'} (main.ts). */
public class SwaggerEnvironmentPostProcessor implements EnvironmentPostProcessor {
  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
    boolean production = "production".equals(environment.getProperty("NODE_ENV"));
    environment.getPropertySources().addFirst(new MapPropertySource("swagger-toggle", Map.of(
        "springdoc.api-docs.enabled", !production,
        "springdoc.swagger-ui.enabled", !production)));
  }
}
