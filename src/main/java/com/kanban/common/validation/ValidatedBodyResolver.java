package com.kanban.common.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanban.common.exception.BadRequestException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Reads the JSON body the way express.json() + Nest's ValidationPipe do:
 * non-JSON / empty bodies validate as {@code {}}, arrays are rejected as an
 * unknown value, and the parsed object is validated with {@link ClassValidator}.
 */
public class ValidatedBodyResolver implements HandlerMethodArgumentResolver {
  private final ObjectMapper mapper;

  public ValidatedBodyResolver(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.hasParameterAnnotation(ValidatedBody.class);
  }

  @Override
  public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest, WebDataBinderFactory binderFactory) throws Exception {
    HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
    Map<String, Object> plain = readBody(request, mapper);
    @SuppressWarnings("unchecked")
    Class<? extends ValidatedDto> type = (Class<? extends ValidatedDto>) parameter.getParameterType();
    return ClassValidator.validate(type, plain);
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> readBody(HttpServletRequest request, ObjectMapper mapper) throws IOException {
    String contentType = request.getContentType();
    if (contentType == null || !contentType.toLowerCase().contains("json")) {
      return new LinkedHashMap<>();
    }
    byte[] bytes = request.getInputStream().readAllBytes();
    String text = new String(bytes, StandardCharsets.UTF_8);
    if (text.isBlank()) {
      return new LinkedHashMap<>();
    }
    Object parsed;
    try {
      parsed = mapper.readValue(text, Object.class);
    } catch (JsonProcessingException e) {
      throw new BadRequestException(e.getOriginalMessage());
    }
    if (parsed instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    if (parsed instanceof List<?>) {
      throw ClassValidator.unknownValue();
    }
    // express.json() (strict mode) rejects primitives at the top level
    throw new BadRequestException("Unexpected token " + text.trim().charAt(0) + " in JSON at position 0");
  }
}
