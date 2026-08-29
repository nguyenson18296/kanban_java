package com.kanban.common.pipes;

import com.kanban.common.exception.BadRequestException;
import com.kanban.common.json.Json;
import com.kanban.common.validation.UuidPatterns;
import java.util.regex.Pattern;

/** Exact ports of Nest's ParseUUIDPipe / ParseIntPipe and the project's ParseProjectIdPipe. */
public final class Pipes {
  private Pipes() {}

  private static final Pattern NUMERIC = Pattern.compile("^-?\\d+$");
  private static final Pattern PROJECT_ID = Pattern.compile("^[A-Za-z0-9]{8}$");

  public static String parseUuid(String value) {
    if (value == null || !UuidPatterns.NEST_PIPE_ALL.matcher(value).matches()) {
      throw new BadRequestException("Validation failed (uuid is expected)");
    }
    return value;
  }

  public static int parseInt(String value) {
    if (value == null || !NUMERIC.matcher(value).matches()) {
      throw new BadRequestException("Validation failed (numeric string is expected)");
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new BadRequestException("Validation failed (numeric string is expected)");
    }
  }

  public static String parseProjectId(String value) {
    if (value == null || !PROJECT_ID.matcher(value).matches()) {
      throw new BadRequestException(Json.map(
          "statusCode", 400,
          "message", "Project ID must be exactly 8 alphanumeric characters (A-Z, a-z, 0-9)",
          "error", "Bad Request"));
    }
    return value;
  }
}
