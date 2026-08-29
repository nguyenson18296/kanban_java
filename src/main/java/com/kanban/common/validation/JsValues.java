package com.kanban.common.validation;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * JavaScript value semantics needed to mirror class-validator / class-transformer:
 * numbers, {@code Number(x)}, {@code parseInt(x)}, {@code new Date(x)}.
 */
public final class JsValues {
  private JsValues() {}

  /** Sentinel for JS {@code undefined} returned from a transform (key treated as absent). */
  public static final Object UNDEFINED = new Object() {
    @Override
    public String toString() {
      return "undefined";
    }
  };

  /** Sentinel for JS {@code NaN} produced by a failed numeric conversion. */
  public static final Double NAN = Double.NaN;

  /** Sentinel for JS {@code Invalid Date}. */
  public static final Object INVALID_DATE = new Object() {
    @Override
    public String toString() {
      return "Invalid Date";
    }
  };

  private static final Pattern LEADING_INT = Pattern.compile("^\\s*([+-]?\\d+)");

  public static boolean isNumber(Object v) {
    return v instanceof Number;
  }

  /** JS {@code Number.isInteger}: a finite number with no fractional part. */
  public static boolean isInteger(Object v) {
    if (!(v instanceof Number n)) {
      return false;
    }
    if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte
        || n instanceof BigInteger) {
      return true;
    }
    double d = n.doubleValue();
    return !Double.isNaN(d) && !Double.isInfinite(d) && Math.rint(d) == d;
  }

  public static double toDouble(Object v) {
    return ((Number) v).doubleValue();
  }

  /** class-transformer {@code @Type(() => Number)}: JS {@code Number(value)}. */
  public static Object jsNumber(Object v) {
    if (v == null) {
      return 0; // Number(null) === 0
    }
    if (v instanceof Number) {
      return v;
    }
    if (v instanceof Boolean b) {
      return b ? 1 : 0;
    }
    if (v instanceof String s) {
      String t = s.trim();
      if (t.isEmpty()) {
        return 0;
      }
      try {
        BigDecimal bd = new BigDecimal(t);
        if (bd.scale() <= 0 && bd.abs().compareTo(new BigDecimal(Long.MAX_VALUE)) <= 0) {
          long l = bd.longValueExact();
          return l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE ? (Object) (int) l : (Object) l;
        }
        return bd.doubleValue();
      } catch (NumberFormatException | ArithmeticException e) {
        return NAN;
      }
    }
    return NAN;
  }

  /** JS {@code Number.parseInt(value, 10)}. */
  public static Object jsParseInt(Object v) {
    if (v == null) {
      return NAN;
    }
    String s;
    if (v instanceof List<?> list) {
      s = list.isEmpty() ? "" : String.valueOf(list.get(0));
    } else {
      s = String.valueOf(v);
    }
    var m = LEADING_INT.matcher(s);
    if (!m.find()) {
      return NAN;
    }
    try {
      long l = Long.parseLong(m.group(1).replace("+", ""));
      return l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE ? (Object) (int) l : (Object) l;
    } catch (NumberFormatException e) {
      return NAN;
    }
  }

  /**
   * JS {@code new Date(value)} for the value shapes JSON can carry. Returns an
   * {@link Instant} or {@link #INVALID_DATE}.
   */
  public static Object jsDate(Object v) {
    if (v instanceof Instant) {
      return v;
    }
    if (v instanceof Number n) {
      double d = n.doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d) || Math.abs(d) > 8.64e15) {
        return INVALID_DATE;
      }
      return Instant.ofEpochMilli((long) d);
    }
    if (v instanceof Boolean b) {
      return Instant.ofEpochMilli(b ? 1 : 0);
    }
    if (v instanceof String s) {
      String t = s.trim();
      if (t.isEmpty()) {
        return INVALID_DATE;
      }
      try {
        return OffsetDateTime.parse(t).toInstant();
      } catch (DateTimeParseException ignored) {
        // fall through
      }
      try {
        return Instant.parse(t);
      } catch (DateTimeParseException ignored) {
        // fall through
      }
      try {
        return LocalDateTime.parse(t).toInstant(ZoneOffset.UTC);
      } catch (DateTimeParseException ignored) {
        // fall through
      }
      try {
        return LocalDate.parse(t).atStartOfDay().toInstant(ZoneOffset.UTC);
      } catch (DateTimeParseException ignored) {
        // fall through
      }
      return INVALID_DATE;
    }
    return INVALID_DATE;
  }
}
