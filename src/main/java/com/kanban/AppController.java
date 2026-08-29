package com.kanban;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Hidden
public class AppController {
  private final AppService appService;

  public AppController(AppService appService) {
    this.appService = appService;
  }

  /** Express sends plain strings as text/html. */
  @GetMapping(value = "", produces = "text/html;charset=utf-8")
  public String getHello() {
    return appService.getHello();
  }
}
