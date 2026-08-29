package com.kanban.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.kanban.common.util.Dates;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Dates serialize like JS {@code Date#toJSON()}: {@code 2025-01-01T00:00:00.000Z}. */
@Configuration
public class JacksonConfig {
  @Bean
  public SimpleModule jsDateModule() {
    SimpleModule module = new SimpleModule("js-dates");
    module.addSerializer(Instant.class, new JsonSerializer<>() {
      @Override
      public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeString(Dates.iso(value));
      }
    });
    module.addSerializer(OffsetDateTime.class, new JsonSerializer<>() {
      @Override
      public void serialize(OffsetDateTime value, JsonGenerator gen, SerializerProvider serializers)
          throws IOException {
        gen.writeString(Dates.iso(value.toInstant()));
      }
    });
    return module;
  }
}
