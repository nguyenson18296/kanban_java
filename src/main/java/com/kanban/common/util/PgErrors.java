package com.kanban.common.util;

import java.sql.SQLException;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/** Reads Postgres error details the way the TypeScript services read {@code error.code / constraint / detail}. */
public final class PgErrors {
  private PgErrors() {}

  public static final String UNIQUE_VIOLATION = "23505";
  public static final String FOREIGN_KEY_VIOLATION = "23503";

  public static SQLException sqlException(Throwable t) {
    Throwable cur = t;
    while (cur != null) {
      if (cur instanceof SQLException sql) {
        return sql;
      }
      cur = cur.getCause();
    }
    return null;
  }

  /** SQLSTATE code, or null. */
  public static String code(Throwable t) {
    SQLException sql = sqlException(t);
    return sql == null ? null : sql.getSQLState();
  }

  public static boolean isCode(Throwable t, String code) {
    return code.equals(code(t));
  }

  /** Violated constraint name, or null. */
  public static String constraint(Throwable t) {
    ServerErrorMessage msg = serverMessage(t);
    return msg == null ? null : msg.getConstraint();
  }

  /** Error detail ("Key (name)=(x) already exists."), or null. */
  public static String detail(Throwable t) {
    ServerErrorMessage msg = serverMessage(t);
    return msg == null ? null : msg.getDetail();
  }

  /** The driver's error message (what {@code (error as Error).message} would carry). */
  public static String message(Throwable t) {
    SQLException sql = sqlException(t);
    return sql != null ? sql.getMessage() : t.getMessage();
  }

  private static ServerErrorMessage serverMessage(Throwable t) {
    Throwable cur = t;
    while (cur != null) {
      if (cur instanceof PSQLException p) {
        return p.getServerErrorMessage();
      }
      cur = cur.getCause();
    }
    return null;
  }
}
