package com.kanban.modules.kanbancolumn;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KanbanColumnRepository extends JpaRepository<KanbanColumn, Integer> {
  List<KanbanColumn> findByIsArchivedFalseOrderByPositionAsc();

  List<KanbanColumn> findByIsArchivedFalseAndProjectIdOrderByPositionAsc(String projectId);
}
