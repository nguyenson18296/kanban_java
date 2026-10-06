package com.kanban.common.exception;

/** Port of NestJS {@code UnsupportedMediaTypeException} (HTTP 415). */
public class UnsupportedMediaTypeException extends HttpException {
  public static final int STATUS = 415;
  public static final String DESCRIPTION = "Unsupported Media Type";

  /** {@code objectOrError} may be a String, a List (message array) or a Map (full body). */
  public UnsupportedMediaTypeException(Object objectOrError) {
    super(createBody(objectOrError, DESCRIPTION, STATUS), STATUS);
  }
}
