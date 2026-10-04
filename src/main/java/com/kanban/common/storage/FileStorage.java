package com.kanban.common.storage;

import java.net.URI;
import java.time.Duration;
import org.springframework.core.io.InputStreamSource;

/** Object storage behind the S3 API (Cloudflare R2; MinIO as the offline alternative). */
public interface FileStorage {
  /**
   * Stores {@code size} bytes from {@code data}; browsers save the object as {@code downloadName}.
   * {@code data} must hand out a fresh stream on every call (a MultipartFile does): the client
   * re-reads the body when it retries.
   */
  void put(String key, InputStreamSource data, long size, String contentType, String downloadName);

  /** Removes the object; a key that does not exist counts as removed. */
  void delete(String key);

  /** A URL that anyone holding it can GET until {@code ttl} has passed. */
  URI signedDownloadUrl(String key, Duration ttl);
}
