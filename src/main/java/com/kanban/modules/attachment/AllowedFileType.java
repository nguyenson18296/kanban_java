package com.kanban.modules.attachment;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The attachment allowlist, recognized from each format's signature bytes. The client's
 * Content-Type is never trusted, and the extension only for Markdown, which has no signature
 * (see {@link #detectMarkdown}). SVG is deliberately absent: it can carry scripts.
 */
public enum AllowedFileType {
  PNG("PNG", "image/png", "png"),
  JPEG("JPEG", "image/jpeg", "jpg", "jpeg"),
  GIF("GIF", "image/gif", "gif"),
  WEBP("WebP", "image/webp", "webp"),
  PDF("PDF", "application/pdf", "pdf"),
  MARKDOWN("Markdown", "text/markdown; charset=utf-8", "md", "markdown");

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

  /**
   * Markdown is plain text with no signature, so it is accepted only when the name says
   * {@code .md}/{@code .markdown} AND the whole content is UTF-8 text: no malformed bytes and no
   * control characters other than tab, CR and LF. A renamed binary fails the second check.
   */
  public static Optional<AllowedFileType> detectMarkdown(String fileName, InputStream content) throws IOException {
    int dot = fileName.lastIndexOf('.');
    String extension = dot > 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    if (!MARKDOWN.extensions.contains(extension)) {
      return Optional.empty();
    }
    CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
    try (Reader reader = new InputStreamReader(content, decoder)) {
      char[] buffer = new char[8192];
      int read;
      while ((read = reader.read(buffer)) != -1) {
        for (int i = 0; i < read; i++) {
          char c = buffer[i];
          if (Character.getType(c) == Character.CONTROL && c != '\t' && c != '\n' && c != '\r') {
            return Optional.empty();
          }
        }
      }
    } catch (CharacterCodingException e) {
      return Optional.empty();
    }
    return Optional.of(MARKDOWN);
  }

  /** "PNG, JPEG, GIF, WebP, PDF, Markdown". */
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
