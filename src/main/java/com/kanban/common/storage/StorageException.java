package com.kanban.common.storage;

/** The only exception {@link FileStorage} throws, so callers never see AWS SDK types. */
public class StorageException extends RuntimeException {
  public StorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
