package com.kanban.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kanban.config.StorageConfig;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Offline: presigning and client construction never open a connection. */
class S3FileStorageTest {
  private final StorageConfig config = new StorageConfig();

  private static StorageProperties properties(String accessKeyId, String secretAccessKey) {
    return new StorageProperties(URI.create("http://localhost:9000"), "us-east-1", "task-attachments",
        accessKeyId, secretAccessKey, true);
  }

  @Test
  @DisplayName("signed URL is path-style, scoped to one key, and expires after the TTL")
  void signedDownloadUrl() {
    StorageProperties p = properties("test-key", "test-secret");
    try (S3Client s3 = config.s3Client(p); S3Presigner presigner = config.s3Presigner(p)) {
      URI url = new S3FileStorage(s3, presigner, p.bucket())
          .signedDownloadUrl("tasks/t1/abc", Duration.ofMinutes(5));

      assertThat(url.getHost()).isEqualTo("localhost");
      assertThat(url.getPort()).isEqualTo(9000);
      assertThat(url.getPath()).isEqualTo("/task-attachments/tasks/t1/abc");
      assertThat(url.getQuery()).contains("X-Amz-Expires=300").contains("X-Amz-Signature=");
    }
  }

  @Test
  @DisplayName("blank keys still build both clients, so the app boots before storage is configured")
  void blankCredentialsDoNotBreakBoot() {
    StorageProperties p = properties("", "");
    assertThatCode(() -> {
      config.s3Client(p).close();
      config.s3Presigner(p).close();
    }).doesNotThrowAnyException();
  }

  /** Like the temp-file stream a MultipartFile hands out: readable once, no mark/reset. */
  private static InputStream readOnce(byte[] bytes) {
    return new FilterInputStream(new ByteArrayInputStream(bytes)) {
      @Override
      public boolean markSupported() {
        return false;
      }
    };
  }

  @Test
  @DisplayName("storage unreachable → StorageException, even though the SDK retries and re-reads the body")
  void unreachableStorageOnRetry() {
    StorageProperties p = new StorageProperties(URI.create("http://127.0.0.1:9"), "us-east-1", "task-attachments",
        "test-key", "test-secret", true);
    byte[] bytes = new byte[64];
    try (S3Client s3 = config.s3Client(p); S3Presigner presigner = config.s3Presigner(p)) {
      S3FileStorage storage = new S3FileStorage(s3, presigner, p.bucket());
      assertThatThrownBy(() -> storage.put("tasks/t1/abc", () -> readOnce(bytes), bytes.length, "image/png", "x.png"))
          .isInstanceOf(StorageException.class);
    }
  }

  @Test
  @DisplayName("ASCII download name → plain quoted filename")
  void asciiContentDisposition() {
    assertThat(S3FileStorage.contentDisposition("login-bug.png"))
        .isEqualTo("attachment; filename=\"login-bug.png\"");
  }

  @Test
  @DisplayName("non-ASCII download name → RFC 5987 filename* so browsers keep the original name")
  void unicodeContentDisposition() {
    assertThat(S3FileStorage.contentDisposition("ảnh chụp.png"))
        .startsWith("attachment; ")
        .contains("filename*=UTF-8''%E1%BA%A3nh%20ch%E1%BB%A5p.png");
  }
}
