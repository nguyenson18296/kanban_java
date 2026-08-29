package com.kanban.common.exception;

/** Port of NestJS {@code ForbiddenException} (HTTP 403). */
public class ForbiddenException extends HttpException {
  public static final int STATUS = 403;
  public static final String DESCRIPTION = "Forbidden";

  public ForbiddenException() {
    super(createBody(null, DESCRIPTION, STATUS), STATUS);
  }

  /** {@code objectOrError} may be a String, a List (message array) or a Map (full body). */
  public ForbiddenException(Object objectOrError) {
    super(createBody(objectOrError, DESCRIPTION, STATUS), STATUS);
  }

  public ForbiddenException(Object objectOrError, String description) {
    super(createBody(objectOrError, description, STATUS), STATUS);
  }
}
