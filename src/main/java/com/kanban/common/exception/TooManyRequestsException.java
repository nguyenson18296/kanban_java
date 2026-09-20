package com.kanban.common.exception;

public class TooManyRequestsException extends HttpException {
  private final long retryAfterSeconds;

  public TooManyRequestsException(String message, long retryAfterSeconds) {
    super(createBody(message, "Too Many Requests", 429), 429);
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public long getRetryAfterSeconds() {
    return retryAfterSeconds;
  }
}
