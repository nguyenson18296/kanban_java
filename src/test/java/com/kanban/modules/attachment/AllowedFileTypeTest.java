package com.kanban.modules.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AllowedFileTypeTest {
  private static byte[] bytes(int... values) {
    byte[] out = new byte[values.length];
    for (int i = 0; i < values.length; i++) {
      out[i] = (byte) values[i];
    }
    return out;
  }

  /** Latin-1 keeps every char <= 0xFF as exactly one byte. */
  private static byte[] latin1(String s) {
    return s.getBytes(StandardCharsets.ISO_8859_1);
  }

  @Test
  @DisplayName("recognizes every allowed signature and maps it to its MIME type")
  void recognizesSignatures() {
    assertThat(AllowedFileType.detect(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D)))
        .contains(AllowedFileType.PNG);
    assertThat(AllowedFileType.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1)))
        .contains(AllowedFileType.JPEG);
    assertThat(AllowedFileType.detect(latin1("GIF87a......"))).contains(AllowedFileType.GIF);
    assertThat(AllowedFileType.detect(latin1("GIF89a......"))).contains(AllowedFileType.GIF);
    assertThat(AllowedFileType.detect(latin1("RIFF$\u0000\u0000\u0000WEBPVP8 "))).contains(AllowedFileType.WEBP);
    assertThat(AllowedFileType.detect(latin1("%PDF-1.7\n%âã"))).contains(AllowedFileType.PDF);

    assertThat(AllowedFileType.PNG.mimeType()).isEqualTo("image/png");
    assertThat(AllowedFileType.JPEG.mimeType()).isEqualTo("image/jpeg");
    assertThat(AllowedFileType.GIF.mimeType()).isEqualTo("image/gif");
    assertThat(AllowedFileType.WEBP.mimeType()).isEqualTo("image/webp");
    assertThat(AllowedFileType.PDF.mimeType()).isEqualTo("application/pdf");
  }

  @Test
  @DisplayName("rejects look-alikes: EXE, SVG, HTML, ZIP, a RIFF that is WAV")
  void rejectsLookAlikes() {
    assertThat(AllowedFileType.detect(bytes('M', 'Z', 0x90, 0, 3, 0, 0, 0, 4, 0, 0, 0))).isEmpty();
    assertThat(AllowedFileType.detect(latin1("<svg xmlns=\"http"))).isEmpty();
    assertThat(AllowedFileType.detect(latin1("<!DOCTYPE html>"))).isEmpty();
    assertThat(AllowedFileType.detect(bytes('P', 'K', 3, 4, 20, 0, 0, 0, 8, 0, 0, 0))).isEmpty();
    assertThat(AllowedFileType.detect(latin1("RIFF$\u0000\u0000\u0000WAVEfmt "))).isEmpty();
  }

  @Test
  @DisplayName("input shorter than a signature is 'not allowed', never an exception")
  void shorterThanSignature() {
    assertThat(AllowedFileType.detect(new byte[0])).isEmpty();
    assertThat(AllowedFileType.detect(latin1("GIF"))).isEmpty();
    assertThat(AllowedFileType.detect(bytes(0x89, 'P'))).isEmpty();
    assertThat(AllowedFileType.detect(latin1("RIFF\u0000\u0000\u0000\u0000WEB"))).isEmpty();
  }

  @Test
  @DisplayName("labels() is the list the 415 message names")
  void labels() {
    assertThat(AllowedFileType.labels()).isEqualTo("PNG, JPEG, GIF, WebP, PDF, Markdown");
  }

  private static Optional<AllowedFileType> markdown(String name, byte[] content) throws IOException {
    return AllowedFileType.detectMarkdown(name, new ByteArrayInputStream(content));
  }

  private static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("Markdown has no signature: a .md/.markdown name with UTF-8 text content is accepted")
  void acceptsMarkdownText() throws IOException {
    assertThat(markdown("notes.md", utf8("# Bug\n\n- step 1\r\n\tindented"))).contains(AllowedFileType.MARKDOWN);
    assertThat(markdown("NOTES.MD", utf8("# Title"))).contains(AllowedFileType.MARKDOWN);
    assertThat(markdown("readme.markdown", utf8("text"))).contains(AllowedFileType.MARKDOWN);
    assertThat(markdown("ghi-chu.md", utf8("\uFEFF# Lỗi đăng nhập — ảnh chụp màn hình"))).contains(AllowedFileType.MARKDOWN);
    assertThat(AllowedFileType.MARKDOWN.mimeType()).isEqualTo("text/markdown; charset=utf-8");
  }

  @Test
  @DisplayName("not Markdown: another extension, binary bytes, broken UTF-8, control characters")
  void rejectsNonMarkdown() throws IOException {
    assertThat(markdown("notes.txt", utf8("# plain text"))).isEmpty();
    assertThat(markdown("notes", utf8("# no extension"))).isEmpty();
    assertThat(markdown("archive.md", bytes('P', 'K', 3, 4, 20, 0, 0, 0, 8, 0))).isEmpty();
    assertThat(markdown("broken.md", bytes('o', 'k', 0xC3, 0x28))).isEmpty();
    assertThat(markdown("bell.md", utf8("text\u0007"))).isEmpty();
  }
}
