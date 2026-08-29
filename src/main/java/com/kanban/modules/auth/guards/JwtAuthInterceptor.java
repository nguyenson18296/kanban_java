package com.kanban.modules.auth.guards;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.kanban.common.exception.UnauthorizedException;
import com.kanban.modules.auth.AuthService;
import com.kanban.modules.auth.JwtService;
import com.kanban.modules.auth.interfaces.JwtPayload;
import com.kanban.modules.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Port of passport-jwt + JwtStrategy: extracts the bearer token, verifies it,
 * reloads the live user (rejecting inactive/missing users) and stores it on the
 * request. Any failure → {@code { message: "Unauthorized", statusCode: 401 }}.
 */
@Component
public class JwtAuthInterceptor implements HandlerInterceptor {
  public static final String USER_ATTRIBUTE = "com.kanban.user";
  private static final Pattern BEARER = Pattern.compile("(\\S+)\\s+(\\S+)");

  private final JwtService jwtService;
  private final AuthService authService;

  public JwtAuthInterceptor(JwtService jwtService, AuthService authService) {
    this.jwtService = jwtService;
    this.authService = authService;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)) {
      return true;
    }
    boolean guarded = method.hasMethodAnnotation(JwtAuth.class)
        || method.getBeanType().isAnnotationPresent(JwtAuth.class);
    if (!guarded) {
      return true;
    }
    String token = extractBearer(request.getHeader("Authorization"));
    if (token == null) {
      throw new UnauthorizedException();
    }
    JwtPayload payload;
    try {
      payload = jwtService.verify(token);
    } catch (JWTVerificationException e) {
      throw new UnauthorizedException();
    }
    User user = authService.validateUserById(payload.sub());
    if (user == null) {
      throw new UnauthorizedException();
    }
    request.setAttribute(USER_ATTRIBUTE, user);
    return true;
  }

  static String extractBearer(String header) {
    if (header == null) {
      return null;
    }
    Matcher m = BEARER.matcher(header);
    if (!m.matches()) {
      return null;
    }
    return "bearer".equalsIgnoreCase(m.group(1)) ? m.group(2) : null;
  }
}
