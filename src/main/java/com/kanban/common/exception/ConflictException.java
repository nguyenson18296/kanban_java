package com.kanban.common.exception;

/** Port of NestJS {@code ConflictException} (HTTP 409). */
public class ConflictException extends HttpException {
  public static final int STATUS = 409;
  public static final String DESCRIPTION = "Conflict";

  public ConflictException() {
    super(createBody(null, DESCRIPTION, STATUS), STATUS);
  }

  /** {@code objectOrError} may be a String, a List (message array) or a Map (full body). */
  public ConflictException(Object objectOrError) {
    super(createBody(objectOrError, DESCRIPTION, STATUS), STATUS);
  }

  public ConflictException(Object objectOrError, String description) {
    super(createBody(objectOrError, description, STATUS), STATUS);
  }
}
