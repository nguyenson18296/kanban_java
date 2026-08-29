package com.kanban.modules.auth.decorators;

import com.kanban.modules.auth.guards.JwtAuthInterceptor;
import com.kanban.modules.user.User;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

public class CurrentUserResolver implements HandlerMethodArgumentResolver {
  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.hasParameterAnnotation(CurrentUser.class);
  }

  @Override
  public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
    User user = (User) webRequest.getAttribute(JwtAuthInterceptor.USER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    CurrentUser ann = parameter.getParameterAnnotation(CurrentUser.class);
    if (ann == null || ann.value().isEmpty()) {
      return user;
    }
    if (user == null) {
      return null;
    }
    return switch (ann.value()) {
      case "id" -> user.getId();
      case "email" -> user.getEmail();
      default -> throw new IllegalArgumentException("Unsupported @CurrentUser property: " + ann.value());
    };
  }
}
