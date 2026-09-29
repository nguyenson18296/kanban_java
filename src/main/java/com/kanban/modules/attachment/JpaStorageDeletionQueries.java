package com.kanban.modules.attachment;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaStorageDeletionQueries implements StorageDeletionQueries {
  @PersistenceContext
  private EntityManager em;

  @Override
  public List<String> findBatch(int limit) {
    List<?> rows = em.createNativeQuery(
        "SELECT storage_key FROM storage_deletions ORDER BY attempts, queued_at LIMIT :limit")
        .setParameter("limit", limit)
        .getResultList();
    return rows.stream().map(String::valueOf).toList();
  }

  @Override
  @Transactional
  public void remove(List<String> keys) {
    if (keys.isEmpty()) {
      return;
    }
    em.createNativeQuery("DELETE FROM storage_deletions WHERE storage_key IN (:keys)")
        .setParameter("keys", keys)
        .executeUpdate();
  }

  @Override
  @Transactional
  public void recordFailures(List<String> keys) {
    if (keys.isEmpty()) {
      return;
    }
    em.createNativeQuery("UPDATE storage_deletions SET attempts = attempts + 1 WHERE storage_key IN (:keys)")
        .setParameter("keys", keys)
        .executeUpdate();
  }
}
