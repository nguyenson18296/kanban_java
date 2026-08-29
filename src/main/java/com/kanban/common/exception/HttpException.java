package com.kanban.common.exception;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Port of NestJS {@code HttpException}. The {@link #getResponse()} object is
 * serialized verbatim as the error body, following Nest's {@code createBody}
 * rules:
 * <ul>
 * <li>no argument → {@code { message: <description>, statusCode }}</li>
 * <li>string/array argument → {@code { message, error: <description>, statusCode }}</li>
 * <li>object argument → the object itself</li>
 * </ul>
 */
public class HttpException extends RuntimeException {
  private final int status;
  private final transient Object response;

  public HttpException(Object response, int status) {
    super(messageOf(response));
    this.response = response;
    this.status = status;
  }

  public int getStatus() {
    return status;
  }

  public Object getResponse() {
    return response;
  }

  /** The JSON body Nest's BaseExceptionFilter would send. */
  public Map<String, Object> toBody() {
    if (response instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      map.forEach((k, v) -> out.put(String.valueOf(k), v));
      return out;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("statusCode", status);
    out.put("message", response);
    return out;
  }

  static Object createBody(Object objectOrError, String description, int statusCode) {
    if (objectOrError == null) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("message", description);
      body.put("statusCode", statusCode);
      return body;
    }
    if (objectOrError instanceof String || objectOrError instanceof List<?> || objectOrError instanceof Number) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("message", objectOrError);
      body.put("error", description);
      body.put("statusCode", statusCode);
      return body;
    }
    return objectOrError;
  }

  private static String messageOf(Object response) {
    if (response instanceof Map<?, ?> map && map.get("message") != null) {
      return String.valueOf(map.get("message"));
    }
    return String.valueOf(response);
  }
}
