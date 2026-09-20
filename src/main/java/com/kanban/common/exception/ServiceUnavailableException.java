package com.kanban.common.exception;

public class ServiceUnavailableException extends HttpException {
  public ServiceUnavailableException(String message) {
    super(createBody(message, "Service Unavailable", 503), 503);
  }
}
