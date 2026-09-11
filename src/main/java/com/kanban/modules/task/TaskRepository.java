package com.kanban.modules.task;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<Task, String> {
  /** relations: assignees, labels, creator, subtasks, subtasks.parent, parent */
  @EntityGraph(attributePaths = {"assignees", "labels", "creator", "subtasks", "subtasks.parent", "parent"})
  @Query("select t from Task t where t.id = :id")
  Optional<Task> findByIdWithFullRelations(@Param("id") String id);

  @EntityGraph(attributePaths = {"assignees", "labels", "creator", "subtasks", "subtasks.parent", "parent"})
  @Query("select t from Task t where t.ticketId = :ticketId")
  Optional<Task> findByTicketIdWithFullRelations(@Param("ticketId") String ticketId);

  /** relations: assignees, labels, creator, subtasks, subtasks.parent (no parent) */
  @EntityGraph(attributePaths = {"assignees", "labels", "creator", "subtasks", "subtasks.parent"})
  @Query("select t from Task t where t.parentId is null and t.columnId in "
      + "(select c.id from KanbanColumn c where c.projectId in :projectIds)")
  List<Task> findTopLevelWithRelationsByProjectIds(@Param("projectIds") List<String> projectIds);

  /** relations: assignees, labels, creator, subtasks, parent */
  @EntityGraph(attributePaths = {"assignees", "labels", "creator", "subtasks", "parent"})
  @Query("select t from Task t where t.parentId = :parentId order by t.position asc")
  List<Task> findSubtasksWithRelations(@Param("parentId") String parentId);

  /** select: id, parent_id */
  @Query("select t.parentId from Task t where t.id = :id")
  List<String> findParentIdRowById(@Param("id") String id);

  /** select: id, column_id */
  @Query("select t.columnId from Task t where t.id = :id")
  List<Integer> findColumnIdRowById(@Param("id") String id);
}
