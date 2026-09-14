package com.kanban.modules.search;

import java.util.List;

/** The ranked full-text task search behind {@code GET /search/tasks} (native SQL, JAV-34). */
public interface TaskSearchQueries {
  /** One page row: the task id, the project the task's column belongs to, and the raw headline. */
  record Hit(String taskId, String projectId, String snippet) {}

  /**
   * {@code ts_headline} wraps every matched word in these two characters (Unicode private-use
   * code points, never present in real text). {@link SearchService} HTML-escapes the headline and
   * then turns them into {@code <mark>} / {@code </mark>}.
   */
  String MARK_START = "";
  String MARK_END = "";

  /**
   * Page of hits ranked by relevance (title matches first), then {@code updated_at DESC}, then id.
   * {@code offset} is a long: {@code (page - 1) * limit} overflows int for large page numbers.
   */
  List<Hit> search(List<String> projectIds, String search, int limit, long offset);

  /** Total number of matching tasks across the given projects. */
  long count(List<String> projectIds, String search);
}
