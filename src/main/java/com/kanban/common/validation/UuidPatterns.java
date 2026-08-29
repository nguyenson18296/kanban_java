package com.kanban.common.validation;

import java.util.regex.Pattern;

/** UUID regexes copied from validator.js (class-validator) and Nest's ParseUUIDPipe. */
public final class UuidPatterns {
  private UuidPatterns() {}

  /** validator.js {@code isUUID(str, 'all')}. */
  public static final Pattern VALIDATOR_ALL = Pattern.compile(
      "^(?:[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
          + "|00000000-0000-0000-0000-000000000000|ffffffff-ffff-ffff-ffff-ffffffffffff)$",
      Pattern.CASE_INSENSITIVE);

  /** validator.js {@code isUUID(str, 4)}. */
  public static final Pattern VALIDATOR_V4 = Pattern.compile(
      "^[0-9A-F]{8}-[0-9A-F]{4}-4[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12}$", Pattern.CASE_INSENSITIVE);

  /** Nest {@code ParseUUIDPipe} without a version ('all'). */
  public static final Pattern NEST_PIPE_ALL = Pattern.compile(
      "^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}$", Pattern.CASE_INSENSITIVE);

  public static boolean isUuid(String s, String version) {
    return switch (version) {
      case "4" -> VALIDATOR_V4.matcher(s).matches();
      default -> VALIDATOR_ALL.matcher(s).matches();
    };
  }
}
