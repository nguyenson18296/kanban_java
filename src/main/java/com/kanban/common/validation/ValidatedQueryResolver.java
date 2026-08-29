package com.kanban.common.validation;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Nest {@code @Query()} DTO: Express "simple" query parsing (repeated keys → array) + ValidationPipe. */
public class ValidatedQueryResolver implements HandlerMethodArgumentResolver {
  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.hasParameterAnnotation(ValidatedQuery.class);
  }

  @Override
  public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
    Map<String, Object> plain = new LinkedHashMap<>();
    for (Map.Entry<String, String[]> e : webRequest.getParameterMap().entrySet()) {
      String[] values = e.getValue();
      if (values.length == 1) {
        plain.put(e.getKey(), values[0]);
      } else {
        plain.put(e.getKey(), (List<Object>) new java.util.ArrayList<Object>(Arrays.asList(values)));
      }
    }
    @SuppressWarnings("unchecked")
    Class<? extends ValidatedDto> type = (Class<? extends ValidatedDto>) parameter.getParameterType();
    return ClassValidator.validate(type, plain);
  }
}
