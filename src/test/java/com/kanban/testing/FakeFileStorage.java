package com.kanban.testing;

import com.kanban.common.storage.FileStorage;
import com.kanban.common.storage.StorageException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.InputStreamSource;

/** In-memory {@link FileStorage}; failures carry internal-looking text so tests can prove it never leaks. */
public class FakeFileStorage implements FileStorage {
  public record StoredFile(byte[] bytes, String contentType, String downloadName) {}

  public final Map<String, StoredFile> files = new LinkedHashMap<>();
  public final List<String> deleted = new ArrayList<>();
  public final List<String> signed = new ArrayList<>();
  public final Set<String> failDeleteKeys = new HashSet<>();
  public boolean failPut;
  public boolean failSign;

  @Override
  public void put(String key, InputStreamSource data, long size, String contentType, String downloadName) {
    if (failPut) {
      throw new StorageException("put failed", new RuntimeException("SignatureDoesNotMatch at s3.internal:9000"));
    }
    try (InputStream in = data.getInputStream()) {
      files.put(key, new StoredFile(in.readAllBytes(), contentType, downloadName));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void delete(String key) {
    if (failDeleteKeys.contains(key)) {
      throw new StorageException("delete failed", new RuntimeException("InternalError at s3.internal:9000"));
    }
    files.remove(key);
    deleted.add(key);
  }

  @Override
  public URI signedDownloadUrl(String key, Duration ttl) {
    if (failSign) {
      throw new StorageException("sign failed", new RuntimeException("Unable to load credentials"));
    }
    signed.add(key);
    return URI.create("https://storage.test/" + key + "?ttl=" + ttl.toSeconds());
  }
}
