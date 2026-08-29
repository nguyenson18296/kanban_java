package com.kanban.modules.comment;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommentRepository extends JpaRepository<Comment, String> {
  @EntityGraph(attributePaths = "author")
  @Query("select c from Comment c where c.id = :id")
  Optional<Comment> findByIdWithAuthor(@Param("id") String id);

  @EntityGraph(attributePaths = "author")
  @Query(value = "select c from Comment c where c.taskId = :taskId",
      countQuery = "select count(c) from Comment c where c.taskId = :taskId")
  Page<Comment> findByTaskIdWithAuthor(@Param("taskId") String taskId, Pageable pageable);
}
