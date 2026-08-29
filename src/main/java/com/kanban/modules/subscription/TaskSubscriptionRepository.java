package com.kanban.modules.subscription;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface TaskSubscriptionRepository extends JpaRepository<TaskSubscription, TaskSubscriptionId> {
  /** INSERT ... ON CONFLICT DO NOTHING (TypeORM {@code orIgnore()}). */
  @Transactional
  @Modifying
  @Query(value = "INSERT INTO task_subscriptions (task_id, user_id, source) "
      + "VALUES (CAST(:taskId AS uuid), CAST(:userId AS uuid), CAST(:source AS task_subscription_source)) "
      + "ON CONFLICT DO NOTHING", nativeQuery = true)
  int insertIgnore(@Param("taskId") String taskId, @Param("userId") String userId, @Param("source") String source);

  @Transactional
  @Modifying
  @Query("delete from TaskSubscription s where s.taskId = :taskId and s.userId = :userId")
  int deleteByTaskIdAndUserId(@Param("taskId") String taskId, @Param("userId") String userId);

  boolean existsByTaskIdAndUserId(String taskId, String userId);

  Optional<TaskSubscription> findByTaskIdAndUserId(String taskId, String userId);

  @Query("select s.userId from TaskSubscription s where s.taskId = :taskId")
  List<String> findUserIdsByTaskId(@Param("taskId") String taskId);

  @EntityGraph(attributePaths = "user")
  @Query("select s from TaskSubscription s where s.taskId = :taskId order by s.createdAt asc")
  List<TaskSubscription> findByTaskIdWithUserOrderByCreatedAtAsc(@Param("taskId") String taskId);
}
