package com.kanban.common.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.core.io.InputStreamSource;
import org.springframework.http.ContentDisposition;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

public class S3FileStorage implements FileStorage {
  private final S3Client s3;
  private final S3Presigner presigner;
  private final String bucket;

  public S3FileStorage(S3Client s3, S3Presigner presigner, String bucket) {
    this.s3 = s3;
    this.presigner = presigner;
    this.bucket = bucket;
  }

  @Override
  public void put(String key, InputStreamSource data, long size, String contentType, String downloadName) {
    // Content-Disposition is stored with the object (Supabase supports it on PutObject), so a
    // signed GET needs no response-header override.
    PutObjectRequest request = PutObjectRequest.builder()
        .bucket(bucket)
        .key(key)
        .contentType(contentType)
        .contentLength(size)
        .contentDisposition(contentDisposition(downloadName))
        .build();
    // A provider, not a one-shot stream: the SDK re-reads the body on every retry, and an upload's
    // temp-file stream cannot be rewound (it would fail with IllegalStateException, not SdkException).
    RequestBody body = RequestBody.fromContentProvider(() -> open(data), size, contentType);
    try {
      s3.putObject(request, body);
    } catch (SdkException e) {
      throw new StorageException("Failed to store " + key, e);
    }
  }

  @Override
  public void delete(String key) {
    try {
      s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    } catch (NoSuchKeyException e) {
      // Already gone -- the state we wanted.
    } catch (SdkException e) {
      throw new StorageException("Failed to delete " + key, e);
    }
  }

  @Override
  public URI signedDownloadUrl(String key, Duration ttl) {
    GetObjectPresignRequest request = GetObjectPresignRequest.builder()
        .signatureDuration(ttl)
        .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
        .build();
    try {
      return presigner.presignGetObject(request).url().toURI();
    } catch (SdkException | URISyntaxException e) {
      throw new StorageException("Failed to sign a download URL for " + key, e);
    }
  }

  private static InputStream open(InputStreamSource data) {
    try {
      return data.getInputStream();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** {@code attachment}, so browsers save rather than render; RFC 5987 {@code filename*} for non-ASCII names. */
  static String contentDisposition(String downloadName) {
    ContentDisposition.Builder builder = ContentDisposition.attachment();
    boolean ascii = StandardCharsets.US_ASCII.newEncoder().canEncode(downloadName);
    return (ascii ? builder.filename(downloadName) : builder.filename(downloadName, StandardCharsets.UTF_8))
        .build()
        .toString();
  }
}
