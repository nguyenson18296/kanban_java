package com.kanban.common.util;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** JavaScript {@code Date#toISOString()} compatible formatting (always 3 fraction digits, UTC). */
public final class Dates {
  private static final DateTimeFormatter ISO =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private Dates() {}

  public static String iso(Instant instant) {
    return instant == null ? null : ISO.format(instant);
  }
}
