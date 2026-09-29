package com.kanban.modules.attachment;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The attachment allowlist, recognized from each format's signature bytes. The client's
 * Content-Type and the file extension are never trusted. SVG is deliberately absent: it can
 * carry scripts.
 */
public enum AllowedFileType {
  PNG("PNG", "image/png", "png"),
  JPEG("JPEG", "image/jpeg", "jpg", "jpeg"),
  GIF("GIF", "image/gif", "gif"),
  WEBP("WebP", "image/webp", "webp"),
  PDF("PDF", "application/pdf", "pdf");

  /** Bytes needed to recognize every type (WebP's marker ends at offset 12). */
  public static final int HEADER_LENGTH = 12;

  private final String label;
  private final String mimeType;
  private final List<String> extensions;

  AllowedFileType(String label, String mimeType, String... extensions) {
    this.label = label;
    this.mimeType = mimeType;
    this.extensions = List.of(extensions);
  }

  public String label() {
    return label;
  }

  public String mimeType() {
    return mimeType;
  }

  /** Lowercase, without the dot; the first is the canonical one. */
  public List<String> extensions() {
    return extensions;
  }

  public static Optional<AllowedFileType> detect(byte[] header) {
    if (startsWith(header, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
      return Optional.of(PNG);
    }
    if (startsWith(header, 0, 0xFF, 0xD8, 0xFF)) {
      return Optional.of(JPEG);
    }
    if (startsWith(header, 0, 'G', 'I', 'F', '8', '7', 'a') || startsWith(header, 0, 'G', 'I', 'F', '8', '9', 'a')) {
      return Optional.of(GIF);
    }
    if (startsWith(header, 0, 'R', 'I', 'F', 'F') && startsWith(header, 8, 'W', 'E', 'B', 'P')) {
      return Optional.of(WEBP);
    }
    if (startsWith(header, 0, '%', 'P', 'D', 'F', '-')) {
      return Optional.of(PDF);
    }
    return Optional.empty();
  }

  /** "PNG, JPEG, GIF, WebP, PDF". */
  public static String labels() {
    return Arrays.stream(values()).map(AllowedFileType::label).collect(Collectors.joining(", "));
  }

  private static boolean startsWith(byte[] data, int offset, int... expected) {
    if (data.length < offset + expected.length) {
      return false;
    }
    for (int i = 0; i < expected.length; i++) {
      if ((data[offset + i] & 0xFF) != expected[i]) {
        return false;
      }
    }
    return true;
  }
}
