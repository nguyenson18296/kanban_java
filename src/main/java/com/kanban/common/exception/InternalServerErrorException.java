package com.kanban.common.exception;

/** Port of NestJS {@code InternalServerErrorException} (HTTP 500). */
public class InternalServerErrorException extends HttpException {
  public static final int STATUS = 500;
  public static final String DESCRIPTION = "Internal Server Error";

  public InternalServerErrorException() {
    super(createBody(null, DESCRIPTION, STATUS), STATUS);
  }

  /** {@code objectOrError} may be a String, a List (message array) or a Map (full body). */
  public InternalServerErrorException(Object objectOrError) {
    super(createBody(objectOrError, DESCRIPTION, STATUS), STATUS);
  }

  public InternalServerErrorException(Object objectOrError, String description) {
    super(createBody(objectOrError, description, STATUS), STATUS);
  }
}
