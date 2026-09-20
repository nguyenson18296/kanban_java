package com.kanban.common.ratelimit;

import com.kanban.common.exception.TooManyRequestsException;
import io.netty.util.NetUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

public class RateLimitInterceptor implements HandlerInterceptor {
  private final RedisRateLimiter limiter;

  public RateLimitInterceptor(RedisRateLimiter limiter) {
    this.limiter = limiter;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (handler instanceof HandlerMethod method) {
      RateLimited rule = method.getMethodAnnotation(RateLimited.class);
      if (rule != null && !"OPTIONS".equals(request.getMethod())) {
        long retryAfter = limiter.retryAfterSeconds(rule.value(), normalizeIp(request.getRemoteAddr()));
        if (retryAfter > 0) {
          throw new TooManyRequestsException("Too many login requests. Please try again later.", retryAfter);
        }
      }
    }
    return true;
  }

  // Trust only the container-resolved address. Tomcat's explicitly configured
  // proxy allowlist handles forwarded headers; this code never reads them.
  static String normalizeIp(String address) {
    if (address == null) {
      return "unknown";
    }
    String literal = address.split("%", 2)[0];
    // Parse only numeric addresses; never resolve a forwarded hostname via DNS.
    byte[] bytes = NetUtil.createByteArrayFromIpAddressString(literal);
    if (bytes == null) {
      return "unknown";
    }
    try {
      return InetAddress.getByAddress(bytes).getHostAddress();
    } catch (UnknownHostException e) {
      return "unknown";
    }
  }
}
