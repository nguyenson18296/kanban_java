package com.kanban.config;

import com.kanban.common.ratelimit.RateLimitInterceptor;
import com.kanban.common.ratelimit.RateLimitProperties;
import com.kanban.common.ratelimit.RedisRateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true")
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {
  @Bean
  public RedisRateLimiter redisRateLimiter(StringRedisTemplate redis, RateLimitProperties properties) {
    return new RedisRateLimiter(redis, properties);
  }

  @Bean
  public WebMvcConfigurer rateLimitWebMvcConfigurer(RedisRateLimiter limiter) {
    return new WebMvcConfigurer() {
      @Override
      public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(limiter)).order(-100);
      }
    };
  }
}
