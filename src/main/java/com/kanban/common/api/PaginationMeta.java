package com.kanban.common.api;

public record PaginationMeta(int page, int limit, long total, long totalPages) {
  public static PaginationMeta of(int page, int limit, long total) {
    return new PaginationMeta(page, limit, total, (long) Math.ceil((double) total / limit));
  }
}
