package com.kanban.modules.attachment;

import java.util.List;

/** The storage_deletions queue, filled by trg_task_attachments_queue_file_deletion (V6). */
public interface StorageDeletionQueries {
  /** Fewest attempts first, then oldest: keys that keep failing can never block the queue. */
  List<String> findBatch(int limit);

  void remove(List<String> keys);

  void recordFailures(List<String> keys);
}
