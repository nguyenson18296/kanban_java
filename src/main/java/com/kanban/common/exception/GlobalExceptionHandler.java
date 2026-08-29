package com.kanban.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Reproduces the response bodies of NestJS' BaseExceptionFilter:
 * <ul>
 * <li>{@link HttpException} → its own body / status</li>
 * <li>unknown route → 404 {@code { message: "Cannot GET /api/x", error: "Not Found", statusCode: 404 }}</li>
 * <li>malformed JSON → 400 {@code { message, error: "Bad Request", statusCode: 400 }}</li>
 * <li>anything else → 500 {@code { statusCode: 500, message: "Internal server error" }}</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(HttpException.class)
  public ResponseEntity<Object> handleHttpException(HttpException ex) {
    return ResponseEntity.status(ex.getStatus()).body(ex.toBody());
  }

  @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class,
      HttpRequestMethodNotSupportedException.class})
  public ResponseEntity<Object> handleNotFound(Exception ex, HttpServletRequest request) {
    String path = request.getRequestURI();
    if (request.getQueryString() != null) {
      path += "?" + request.getQueryString();
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("message", "Cannot " + request.getMethod() + " " + path);
    body.put("error", "Not Found");
    body.put("statusCode", 404);
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Object> handleUnreadable(HttpMessageNotReadableException ex) {
    Throwable cause = ex.getMostSpecificCause();
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new BadRequestException(cause.getMessage()).toBody());
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Object> handleUnknown(Exception ex) {
    log.error("Unhandled exception", ex);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("statusCode", 500);
    body.put("message", "Internal server error");
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
  }
}
