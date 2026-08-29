package com.kanban.common.exception;

/** Port of NestJS {@code NotFoundException} (HTTP 404). */
public class NotFoundException extends HttpException {
  public static final int STATUS = 404;
  public static final String DESCRIPTION = "Not Found";

  public NotFoundException() {
    super(createBody(null, DESCRIPTION, STATUS), STATUS);
  }

  /** {@code objectOrError} may be a String, a List (message array) or a Map (full body). */
  public NotFoundException(Object objectOrError) {
    super(createBody(objectOrError, DESCRIPTION, STATUS), STATUS);
  }

  public NotFoundException(Object objectOrError, String description) {
    super(createBody(objectOrError, description, STATUS), STATUS);
  }
}
