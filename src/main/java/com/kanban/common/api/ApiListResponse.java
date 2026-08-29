package com.kanban.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** {@code { data, status, success, message? }} list envelope (message omitted when null). */
public record ApiListResponse<T>(
    List<T> data,
    int status,
    boolean success,
    @JsonInclude(JsonInclude.Include.NON_NULL) String message) {

  public static <T> ApiListResponse<T> ok(List<T> data) {
    return new ApiListResponse<>(data, 200, true, null);
  }
}
