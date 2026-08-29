package com.kanban.common.exception;

/** Port of NestJS {@code UnauthorizedException} (HTTP 401). */
public class UnauthorizedException extends HttpException {
  public static final int STATUS = 401;
  public static final String DESCRIPTION = "Unauthorized";

  public UnauthorizedException() {
    super(createBody(null, DESCRIPTION, STATUS), STATUS);
  }

  /** {@code objectOrError} may be a String, a List (message array) or a Map (full body). */
  public UnauthorizedException(Object objectOrError) {
    super(createBody(objectOrError, DESCRIPTION, STATUS), STATUS);
  }

  public UnauthorizedException(Object objectOrError, String description) {
    super(createBody(objectOrError, description, STATUS), STATUS);
  }
}
