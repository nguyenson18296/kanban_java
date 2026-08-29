package com.kanban;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class KanbanApplication {
  public static void main(String[] args) {
    SpringApplication.run(KanbanApplication.class, args);
  }
}
