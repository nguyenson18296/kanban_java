package com.kanban.modules.dependency;

import com.kanban.modules.dependency.dto.TaskSummaryDto;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DependencyRepository extends JpaRepository<TaskDependency, TaskDependencyId> {
  /** Tasks that block :taskId. */
  @Query("select new com.kanban.modules.dependency.dto.TaskSummaryDto("
      + "t.id, t.ticketId, t.title, t.status, t.columnId) "
      + "from TaskDependency d, Task t where t.id = d.blockingTaskId and d.blockedTaskId = :taskId "
      + "order by t.ticketNumber asc")
  List<TaskSummaryDto> findBlockedBy(@Param("taskId") String taskId);

  /** Tasks that :taskId blocks. */
  @Query("select new com.kanban.modules.dependency.dto.TaskSummaryDto("
      + "t.id, t.ticketId, t.title, t.status, t.columnId) "
      + "from TaskDependency d, Task t where t.id = d.blockedTaskId and d.blockingTaskId = :taskId "
      + "order by t.ticketNumber asc")
  List<TaskSummaryDto> findBlocks(@Param("taskId") String taskId);

  /** Of :candidateIds, those that already block :taskId — the diff that keeps activity honest. */
  @Query("select d.blockingTaskId from TaskDependency d "
      + "where d.blockedTaskId = :taskId and d.blockingTaskId in :candidateIds")
  List<String> findExistingBlockers(@Param("taskId") String taskId,
      @Param("candidateIds") List<String> candidateIds);

  /** ON CONFLICT DO NOTHING: a concurrent duplicate must not become a 500. */
  @Transactional
  @Modifying
  @Query(value = "INSERT INTO task_dependencies (blocking_task_id, blocked_task_id, created_by) "
      + "VALUES (CAST(:blockingTaskId AS uuid), CAST(:blockedTaskId AS uuid), CAST(:createdBy AS uuid)) "
      + "ON CONFLICT DO NOTHING", nativeQuery = true)
  int insertIgnore(@Param("blockingTaskId") String blockingTaskId,
      @Param("blockedTaskId") String blockedTaskId, @Param("createdBy") String createdBy);

  @Transactional
  @Modifying
  @Query("delete from TaskDependency d where d.blockedTaskId = :taskId and d.blockingTaskId in :blockingTaskIds")
  int deleteEdges(@Param("taskId") String taskId, @Param("blockingTaskIds") List<String> blockingTaskIds);
}
