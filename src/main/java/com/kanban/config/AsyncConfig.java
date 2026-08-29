package com.kanban.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Executor for {@code @Async} event listeners (Nest's EventEmitter2 fire-and-forget semantics). */
@Configuration
public class AsyncConfig {
  @Bean(name = "eventExecutor")
  public Executor eventExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(8);
    executor.setQueueCapacity(1000);
    executor.setThreadNamePrefix("events-");
    executor.initialize();
    return executor;
  }
}
