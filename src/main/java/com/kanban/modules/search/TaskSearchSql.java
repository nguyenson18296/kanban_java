package com.kanban.modules.search;

/**
 * The one full-text predicate over {@code tasks.search_vector} (V4), shared by the board filter
 * ({@code JpaBoardQueries}) and {@code GET /search/tasks} ({@code JpaTaskSearchQueries}) so every
 * query parses its input with the text-search configuration the generated column was built with.
 */
public final class TaskSearchSql {
  /**
   * {@code simple}: task text is mixed Vietnamese/English/Finnish, so no stemming and no stop-word
   * removal — every word in every language is searchable and matching is predictable whole-word
   * matching (the trade-off: {@code login} does not match {@code logins}).
   */
  public static final String CONFIG = "simple";

  /**
   * Parses raw user input into a tsquery. It tolerates any syntax and any characters (unbalanced
   * quotes, operators, punctuation-only input all parse), but a single token of 2047+ bytes still
   * raises {@code word is too long in tsquery} — so every caller must cap the input length before
   * binding it ({@code @MaxLength(200)} on {@code TaskSearchQueryDto.q} and {@code BoardQueryDto.search}).
   * Binds {@code :search}.
   */
  public static final String TSQUERY = "websearch_to_tsquery('" + CONFIG + "', :search)";

  /** {@code WHERE} fragment for a {@code tasks t} alias. */
  public static final String MATCHES = "t.search_vector @@ " + TSQUERY;

  private TaskSearchSql() {}
}
