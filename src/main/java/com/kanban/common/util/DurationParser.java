package com.kanban.common.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Port of the vercel/ms grammar used by jsonwebtoken's {@code expiresIn} ("1h", "30d", "3600"). */
public final class DurationParser {
  private DurationParser() {}

  private static final Pattern MS = Pattern.compile(
      "^(-?(?:\\d+)?\\.?\\d+) *(milliseconds?|msecs?|ms|seconds?|secs?|s|minutes?|mins?|m|hours?|hrs?|h|days?|d|weeks?|w|years?|yrs?|y)?$",
      Pattern.CASE_INSENSITIVE);

  /** Returns the duration in seconds, as jsonwebtoken would compute for {@code exp}. */
  public static long toSeconds(String value) {
    if (value == null) {
      throw new IllegalArgumentException("duration is required");
    }
    Matcher m = MS.matcher(value.trim());
    if (!m.matches()) {
      throw new IllegalArgumentException("Invalid duration: " + value);
    }
    double n = Double.parseDouble(m.group(1));
    String unit = m.group(2) == null ? "ms" : m.group(2).toLowerCase();
    double ms = switch (unit) {
      case "years", "year", "yrs", "yr", "y" -> n * 365.25 * 24 * 3600 * 1000;
      case "weeks", "week", "w" -> n * 7 * 24 * 3600 * 1000;
      case "days", "day", "d" -> n * 24 * 3600 * 1000;
      case "hours", "hour", "hrs", "hr", "h" -> n * 3600 * 1000;
      case "minutes", "minute", "mins", "min", "m" -> n * 60 * 1000;
      case "seconds", "second", "secs", "sec", "s" -> n * 1000;
      default -> n;
    };
    if (m.group(2) == null) {
      // jsonwebtoken: a bare numeric string is interpreted as seconds
      return (long) n;
    }
    return (long) Math.floor(ms / 1000);
  }
}
