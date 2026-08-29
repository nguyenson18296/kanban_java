package com.kanban;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of app.controller.spec.ts */
class AppControllerTest {
  @Test
  @DisplayName("getHello should return \"Hello World!\"")
  void getHello() {
    AppController controller = new AppController(new AppService());
    assertThat(controller.getHello()).isEqualTo("Hello World!");
  }
}
