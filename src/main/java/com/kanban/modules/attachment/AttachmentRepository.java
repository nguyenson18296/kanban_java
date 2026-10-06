package com.kanban.modules.attachment;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttachmentRepository extends JpaRepository<TaskAttachment, String> {
  @EntityGraph(attributePaths = "uploader")
  @Query(value = "select a from TaskAttachment a where a.taskId = :taskId",
      countQuery = "select count(a) from TaskAttachment a where a.taskId = :taskId")
  Page<TaskAttachment> findByTaskIdWithUploader(@Param("taskId") String taskId, Pageable pageable);

  @EntityGraph(attributePaths = "uploader")
  @Query("select a from TaskAttachment a where a.id = :id")
  Optional<TaskAttachment> findByIdWithUploader(@Param("id") String id);

  /** Scoped to the task in the path, so an attachment id from another task is simply not found. */
  Optional<TaskAttachment> findByIdAndTaskId(String id, String taskId);
}
