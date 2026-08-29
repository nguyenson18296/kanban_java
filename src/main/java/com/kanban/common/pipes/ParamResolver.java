package com.kanban.common.pipes;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerMapping;

public class ParamResolver implements HandlerMethodArgumentResolver {
  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.hasParameterAnnotation(Param.class);
  }

  @Override
  public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
    Param param = parameter.getParameterAnnotation(Param.class);
    HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
    @SuppressWarnings("unchecked")
    Map<String, String> vars = (Map<String, String>) request
        .getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    String raw = vars == null ? null : vars.get(param.value());
    return switch (param.pipe()) {
      case UUID -> Pipes.parseUuid(raw);
      case INT -> Pipes.parseInt(raw);
      case PROJECT_ID -> Pipes.parseProjectId(raw);
      case NONE -> raw;
    };
  }
}
