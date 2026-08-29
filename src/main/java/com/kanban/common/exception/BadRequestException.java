package com.kanban.common.exception;

/** Port of NestJS {@code BadRequestException} (HTTP 400). */
public class BadRequestException extends HttpException {
  public static final int STATUS = 400;
  public static final String DESCRIPTION = "Bad Request";

  public BadRequestException() {
    super(createBody(null, DESCRIPTION, STATUS), STATUS);
  }

  /** {@code objectOrError} may be a String, a List (message array) or a Map (full body). */
  public BadRequestException(Object objectOrError) {
    super(createBody(objectOrError, DESCRIPTION, STATUS), STATUS);
  }

  public BadRequestException(Object objectOrError, String description) {
    super(createBody(objectOrError, description, STATUS), STATUS);
  }
}
