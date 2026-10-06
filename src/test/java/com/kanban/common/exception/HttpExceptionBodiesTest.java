package com.kanban.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class HttpExceptionBodiesTest {
  @Test
  @DisplayName("413 file-too-large body names the limit in Nest's createBody shape")
  void fileTooLarge() {
    PayloadTooLargeException e = PayloadTooLargeException.forFileSize(DataSize.ofMegabytes(10));
    assertThat(e.getStatus()).isEqualTo(413);
    assertThat(e.toBody()).containsExactly(
        entry("message", "File is too large. The maximum size is 10 MB."),
        entry("error", "Payload Too Large"),
        entry("statusCode", 413));
  }

  @Test
  @DisplayName("limits that are not whole megabytes read naturally")
  void describesOtherSizes() {
    assertThat(PayloadTooLargeException.forFileSize(DataSize.ofKilobytes(512)).getMessage())
        .isEqualTo("File is too large. The maximum size is 512 KB.");
    assertThat(PayloadTooLargeException.forFileSize(DataSize.ofBytes(1500)).getMessage())
        .isEqualTo("File is too large. The maximum size is 1500 bytes.");
  }

  @Test
  @DisplayName("415 body in Nest's createBody shape")
  void unsupportedMediaType() {
    UnsupportedMediaTypeException e = new UnsupportedMediaTypeException("nope");
    assertThat(e.getStatus()).isEqualTo(415);
    assertThat(e.toBody()).containsExactly(
        entry("message", "nope"), entry("error", "Unsupported Media Type"), entry("statusCode", 415));
  }
}
