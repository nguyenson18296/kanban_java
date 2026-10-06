package com.kanban.modules.attachment;

import com.kanban.common.storage.FileStorage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes the stored files of deleted attachment rows. No row lock and no transaction spans
 * the storage calls, so no pooled DB connection waits on storage; deleting a file is
 * idempotent, so two app instances at worst delete one file twice.
 */
@Component
public class StorageDeletionJob {
  private static final Logger log = LoggerFactory.getLogger(StorageDeletionJob.class);
  static final int BATCH_SIZE = 100;

  private final StorageDeletionQueries queries;
  private final FileStorage fileStorage;

  public StorageDeletionJob(StorageDeletionQueries queries, FileStorage fileStorage) {
    this.queries = queries;
    this.fileStorage = fileStorage;
  }

  @Scheduled(fixedDelay = 60, initialDelay = 60, timeUnit = TimeUnit.SECONDS)
  public void run() {
    List<String> keys = queries.findBatch(BATCH_SIZE);
    if (keys.isEmpty()) {
      return;
    }
    List<String> deleted = new ArrayList<>();
    List<String> failed = new ArrayList<>();
    for (String key : keys) {
      try {
        fileStorage.delete(key);
        deleted.add(key);
      } catch (RuntimeException e) {
        // Any failure, not just StorageException: an uncounted key stays first in the queue
        // (ORDER BY attempts) and would stall every later deletion.
        log.warn("Failed to delete stored file {}; will retry", key, e);
        failed.add(key);
      }
    }
    queries.remove(deleted);
    queries.recordFailures(failed);
  }
}
