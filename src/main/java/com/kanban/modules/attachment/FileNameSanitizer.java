package com.kanban.modules.attachment;

import java.util.Locale;
import java.util.regex.Pattern;

/** Makes a client-supplied file name safe to store, display and hand back as a download name. */
public final class FileNameSanitizer {
  static final int MAX_LENGTH = 255;
  static final String FALLBACK = "attachment";
  private static final int MAX_EXTENSION = 16;
  /** Control characters (Cc) and invisible format characters (Cf) such as U+202E, which can
   *  make "invoice\u202Efdp.exe" display as "invoiceexe.pdf". */
  private static final Pattern UNSAFE = Pattern.compile("[\\p{Cc}\\p{Cf}]");

  private FileNameSanitizer() {}

  public static String sanitize(String original) {
    if (original == null) {
      return FALLBACK;
    }
    // Browsers send "C:\fakepath\x.png"; tools can send "../../x.png". Keep the last segment.
    int lastSeparator = Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\'));
    String name = UNSAFE.matcher(original.substring(lastSeparator + 1)).replaceAll("").strip();
    if (name.isEmpty() || name.equals(".") || name.equals("..")) {
      return FALLBACK;
    }
    return truncate(name);
  }

  /**
   * Appends the detected type's extension unless the name already ends in one of its spellings,
   * so a name can never disguise the content: PDF-headed bytes named "report.bat" download as
   * "report.bat.pdf", not as a script.
   */
  public static String withExtensionOf(String name, AllowedFileType type) {
    int dot = name.lastIndexOf('.');
    String extension = dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    if (type.extensions().contains(extension)) {
      return name;
    }
    return truncate(name + "." + type.extensions().get(0));
  }

  /** VARCHAR(255) counts characters, so cut by code point, keeping a short extension. */
  private static String truncate(String name) {
    if (name.codePointCount(0, name.length()) <= MAX_LENGTH) {
      return name;
    }
    int dot = name.lastIndexOf('.');
    String extension = dot > 0 && name.length() - dot <= MAX_EXTENSION ? name.substring(dot) : "";
    String base = name.substring(0, name.length() - extension.length());
    int keep = MAX_LENGTH - extension.codePointCount(0, extension.length());
    return base.substring(0, base.offsetByCodePoints(0, keep)) + extension;
  }
}
