package com.kanban.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kanban.config.StorageConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Opt-in: docker compose -f compose.storage.yml up -d --wait && mvn -Pstorage-it verify.
 * Built through the real StorageConfig beans (checksum settings included). Uses its own bucket
 * and a unique key prefix; deletes everything it creates.
 */
class S3FileStorageIT {
  private static final String BUCKET = "kanban-it";

  private final String prefix = "it/" + UUID.randomUUID() + "/";
  private final HttpClient http = HttpClient.newHttpClient();
  private S3Client s3;
  private S3Presigner presigner;
  private S3FileStorage storage;

  private static StorageProperties properties(String secret) {
    return new StorageProperties(URI.create(System.getProperty("storage.it.endpoint", "http://127.0.0.1:9000")),
        "us-east-1", BUCKET, System.getProperty("storage.it.access-key", "minioadmin"), secret, true);
  }

  @BeforeEach
  void connect() {
    StorageConfig config = new StorageConfig();
    StorageProperties p = properties(System.getProperty("storage.it.secret-key", "minioadmin"));
    s3 = config.s3Client(p);
    presigner = config.s3Presigner(p);
    storage = new S3FileStorage(s3, presigner, BUCKET);
    try {
      s3.createBucket(b -> b.bucket(BUCKET));
    } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException ignored) {
      // created by an earlier run
    }
  }

  @AfterEach
  void cleanUp() {
    s3.listObjectsV2Paginator(b -> b.bucket(BUCKET).prefix(prefix)).contents()
        .forEach(o -> s3.deleteObject(b -> b.bucket(BUCKET).key(o.key())));
    s3.close();
    presigner.close();
    http.close();
  }

  @Test
  @DisplayName("put → signed GET returns the same bytes, the detected type and the original name")
  void putThenSignedGet() throws Exception {
    byte[] bytes = "%PDF-1.7 jsp40".getBytes(StandardCharsets.US_ASCII);
    String key = prefix + "doc";
    storage.put(key, new ByteArrayResource(bytes), bytes.length, "application/pdf", "báo cáo.pdf");

    URI url = storage.signedDownloadUrl(key, Duration.ofMinutes(1));
    HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(url).GET().build(),
        HttpResponse.BodyHandlers.ofByteArray());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo(bytes);
    assertThat(response.headers().firstValue("Content-Type")).contains("application/pdf");
    assertThat(response.headers().firstValue("Content-Disposition").orElseThrow())
        .startsWith("attachment")
        .contains("filename*=UTF-8''b%C3%A1o%20c%C3%A1o.pdf");
  }

  @Test
  @DisplayName("an expired signed URL is refused")
  void expiredUrlRefused() throws Exception {
    String key = prefix + "short";
    storage.put(key, new ByteArrayResource(new byte[] {1}), 1, "image/png", "x.png");
    URI url = storage.signedDownloadUrl(key, Duration.ofSeconds(1));

    Thread.sleep(2_000);
    HttpResponse<String> response = http.send(HttpRequest.newBuilder(url).GET().build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(403);
  }

  @Test
  @DisplayName("delete removes the object; deleting a missing key is not an error")
  void deleteIsIdempotent() {
    String key = prefix + "gone";
    storage.put(key, new ByteArrayResource(new byte[] {1}), 1, "image/png", "x.png");

    storage.delete(key);

    assertThatThrownBy(() -> s3.headObject(b -> b.bucket(BUCKET).key(key)))
        .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.statusCode()).isEqualTo(404));
    assertThatCode(() -> storage.delete(prefix + "never-existed")).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("wrong credentials surface as StorageException, never an AWS type")
  void wrongCredentials() {
    StorageConfig config = new StorageConfig();
    StorageProperties bad = properties("wrong-secret");
    try (S3Client badClient = config.s3Client(bad); S3Presigner badPresigner = config.s3Presigner(bad)) {
      S3FileStorage badStorage = new S3FileStorage(badClient, badPresigner, BUCKET);
      assertThatThrownBy(() -> badStorage.put(prefix + "x", new ByteArrayResource(new byte[] {1}), 1,
          "image/png", "x.png")).isInstanceOf(StorageException.class);
    }
  }
}
