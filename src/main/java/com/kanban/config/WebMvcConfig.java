package com.kanban.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanban.common.pipes.ParamResolver;
import com.kanban.common.validation.ValidatedBodyResolver;
import com.kanban.common.validation.ValidatedQueryResolver;
import com.kanban.modules.auth.decorators.CurrentUserResolver;
import com.kanban.modules.auth.guards.JwtAuthInterceptor;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * main.ts equivalent: global "/api" prefix, permissive CORS ({@code origin: '*'},
 * no credentials), the JWT guard interceptor, and the Nest-style argument resolvers.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
  private final ObjectMapper objectMapper;
  private final JwtAuthInterceptor jwtAuthInterceptor;

  public WebMvcConfig(ObjectMapper objectMapper, JwtAuthInterceptor jwtAuthInterceptor) {
    this.objectMapper = objectMapper;
    this.jwtAuthInterceptor = jwtAuthInterceptor;
  }

  @Override
  public void configurePathMatch(PathMatchConfigurer configurer) {
    configurer.addPathPrefix("/api", c -> c.getPackageName().startsWith("com.kanban"));
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/**")
        .allowedOrigins("*")
        .allowedMethods("GET", "HEAD", "PUT", "PATCH", "POST", "DELETE")
        .allowedHeaders("*")
        .allowCredentials(false);
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(jwtAuthInterceptor);
  }

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new ParamResolver());
    resolvers.add(new ValidatedBodyResolver(objectMapper));
    resolvers.add(new ValidatedQueryResolver());
    resolvers.add(new CurrentUserResolver());
  }
}
