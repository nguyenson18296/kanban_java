package com.kanban.modules.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import com.kanban.testing.FakeFileStorage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StorageDeletionJobTest {
  private FakeQueries queries;
  private FakeFileStorage storage;
  private StorageDeletionJob job;

  /** The storage_deletions table as a list; records what the job asks of it. */
  static class FakeQueries implements StorageDeletionQueries {
    final List<String> queue = new ArrayList<>();
    final List<List<String>> removed = new ArrayList<>();
    final List<List<String>> failures = new ArrayList<>();
    int requestedLimit;

    @Override
    public List<String> findBatch(int limit) {
      requestedLimit = limit;
      return List.copyOf(queue.subList(0, Math.min(limit, queue.size())));
    }

    @Override
    public void remove(List<String> keys) {
      removed.add(List.copyOf(keys));
      queue.removeAll(keys);
    }

    @Override
    public void recordFailures(List<String> keys) {
      failures.add(List.copyOf(keys));
    }
  }

  @BeforeEach
  void setUp() {
    queries = new FakeQueries();
    storage = new FakeFileStorage();
    job = new StorageDeletionJob(queries, storage);
  }

  @Test
  @DisplayName("empty queue → no storage call and no writes")
  void emptyQueue() {
    job.run();

    assertThat(queries.requestedLimit).isEqualTo(StorageDeletionJob.BATCH_SIZE);
    assertThat(storage.deleted).isEmpty();
    assertThat(queries.removed).isEmpty();
    assertThat(queries.failures).isEmpty();
  }

  @Test
  @DisplayName("deleted files leave the queue")
  void success() {
    queries.queue.addAll(List.of("tasks/t/a", "tasks/t/b"));

    job.run();

    assertThat(storage.deleted).containsExactly("tasks/t/a", "tasks/t/b");
    assertThat(queries.queue).isEmpty();
    assertThat(queries.failures).containsExactly(List.of());
  }

  @Test
  @DisplayName("a failing key stays queued with one more attempt; the others still go")
  void partialFailure() {
    queries.queue.addAll(List.of("tasks/t/a", "tasks/t/bad", "tasks/t/c"));
    storage.failDeleteKeys.add("tasks/t/bad");

    job.run();

    assertThat(queries.removed).containsExactly(List.of("tasks/t/a", "tasks/t/c"));
    assertThat(queries.failures).containsExactly(List.of("tasks/t/bad"));
    assertThat(queries.queue).containsExactly("tasks/t/bad");
  }

  @Test
  @DisplayName("an unexpected error on one key is counted as a failed attempt, so it cannot stall the queue")
  void unexpectedErrorCountsAsFailure() {
    queries.queue.addAll(List.of("tasks/t/a", "tasks/t/odd", "tasks/t/c"));
    FakeFileStorage odd = new FakeFileStorage() {
      @Override
      public void delete(String key) {
        if (key.equals("tasks/t/odd")) {
          throw new IllegalStateException("not an SdkException");
        }
        super.delete(key);
      }
    };

    new StorageDeletionJob(queries, odd).run();

    assertThat(queries.removed).containsExactly(List.of("tasks/t/a", "tasks/t/c"));
    assertThat(queries.failures).containsExactly(List.of("tasks/t/odd"));
  }
}
