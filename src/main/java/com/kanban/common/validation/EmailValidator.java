package com.kanban.common.validation;

import java.util.regex.Pattern;

/**
 * Port of validator.js {@code isEmail} with class-validator's default options
 * (no display name, UTF-8 local part allowed, TLD required, max length 254,
 * local part ≤ 64 bytes, dot-atom rules).
 */
public final class EmailValidator {
  private EmailValidator() {}

  private static final Pattern EMAIL_USER_UTF8_PART = Pattern.compile(
      "^[a-z\\d!#$%&'*+\\-/=?^_`{|}~\\u00A1-\\uD7FF\\uF900-\\uFDCF\\uFDF0-\\uFFEF]+$",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern QUOTED_EMAIL_USER_UTF8 = Pattern.compile(
      "^([\\s\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f\\x21\\x23-\\x5b\\x5d-\\x7e\\u00A0-\\uD7FF\\uF900-\\uFDCF\\uFDF0-\\uFFEF]"
          + "|(\\\\[\\x01-\\x09\\x0b\\x0c\\x0d-\\x7f\\u00A0-\\uD7FF\\uF900-\\uFDCF\\uFDF0-\\uFFEF]))*$",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern IPV4 = Pattern.compile(
      "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
  private static final Pattern FQDN_PART = Pattern.compile("^[a-z\\u00a1-\\uffff0-9-]+$", Pattern.CASE_INSENSITIVE);
  private static final Pattern FQDN_TLD = Pattern.compile("^([a-z\\u00a1-\\uffff]{2,}|xn[a-z0-9-]{2,})$",
      Pattern.CASE_INSENSITIVE);

  public static boolean isEmail(String str) {
    if (str == null) {
      return false;
    }
    if (str.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 254) {
      return false;
    }
    int at = str.lastIndexOf('@');
    if (at < 0) {
      return false;
    }
    String user = str.substring(0, at);
    String domain = str.substring(at + 1);
    String lowerDomain = domain.toLowerCase();

    if (lowerDomain.equals("gmail.com") || lowerDomain.equals("googlemail.com")) {
      String username = user.replace("\"", "").toLowerCase().split("\\+")[0];
      if (!isByteLength(username, 6, 30)) {
        return false;
      }
      for (String part : username.split("\\.")) {
        if (!Pattern.compile("^[a-z\\d]+$").matcher(part).matches()) {
          return false;
        }
      }
    }
    if (!isByteLength(user, 0, 64) || !isByteLength(domain, 0, 254)) {
      return false;
    }
    if (!isFqdn(domain)) {
      if (!(domain.startsWith("[") && domain.endsWith("]"))) {
        return false;
      }
      String ip = domain.substring(1, domain.length() - 1);
      if (ip.isEmpty() || !IPV4.matcher(ip).matches()) {
        return false;
      }
    }
    if (user.startsWith("\"")) {
      String inner = user.substring(1, Math.max(1, user.length() - 1));
      return QUOTED_EMAIL_USER_UTF8.matcher(inner).matches();
    }
    String[] parts = user.split("\\.", -1);
    for (String part : parts) {
      if (!EMAIL_USER_UTF8_PART.matcher(part).matches()) {
        return false;
      }
    }
    return true;
  }

  private static boolean isByteLength(String s, int min, int max) {
    int len = s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    return len >= min && len <= max;
  }

  /** validator.js isFQDN with default options (require_tld, no underscores, no trailing dot). */
  static boolean isFqdn(String str) {
    if (str.isEmpty()) {
      return false;
    }
    String[] parts = str.split("\\.", -1);
    String tld = parts[parts.length - 1];
    if (parts.length < 2) {
      return false;
    }
    if (!FQDN_TLD.matcher(tld).matches()) {
      return false;
    }
    if (tld.matches(".*\\s.*") || tld.matches("^\\d+$")) {
      return false;
    }
    for (String part : parts) {
      if (part.length() > 63) {
        return false;
      }
      if (!FQDN_PART.matcher(part).matches()) {
        return false;
      }
      if (part.matches("[\\uff01-\\uff5e]")) {
        return false;
      }
      if (part.matches("^-.*|.*-$")) {
        return false;
      }
    }
    return true;
  }
}
