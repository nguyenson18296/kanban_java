package com.kanban.common.api;

import java.util.List;

/** {@code { data, meta: { page, limit, total, totalPages } }} for offset-paginated lists. */
public record PaginatedResponse<T>(List<T> data, PaginationMeta meta) {}
