package com.kanban.common.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extract @mentioned users from HTML content. Returns {ids, names} — UUIDs from
 * data-mention-id (preferred), full_names from data-mention or plain @Name as fallback.
 */
public final class ParseMentions {
  private ParseMentions() {}

  public record Mentions(List<String> ids, List<String> names) {}

  private static final Pattern ID = Pattern.compile("data-mention-id=\"([^\"]+)\"");
  private static final Pattern SPAN = Pattern.compile("<span[^>]*data-mention=\"([^\"]+)\"[^>]*>");
  private static final Pattern TAG = Pattern.compile("<[^>]*>");
  private static final Pattern PLAIN = Pattern.compile("@([A-Z][a-zA-Z]+(?:\\s[A-Z][a-zA-Z]+)+)");

  public static Mentions parseMentions(String html) {
    Set<String> ids = new LinkedHashSet<>();
    Set<String> names = new LinkedHashSet<>();

    Matcher m = ID.matcher(html);
    while (m.find()) {
      ids.add(m.group(1).trim());
    }
    m = SPAN.matcher(html);
    while (m.find()) {
      if (!m.group(0).contains("data-mention-id")) {
        names.add(m.group(1).trim());
      }
    }
    String plainText = TAG.matcher(html).replaceAll(" ");
    m = PLAIN.matcher(plainText);
    while (m.find()) {
      names.add(m.group(1).trim());
    }
    return new Mentions(new ArrayList<>(ids), new ArrayList<>(names));
  }
}
