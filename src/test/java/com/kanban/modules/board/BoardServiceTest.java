package com.kanban.modules.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kanban.modules.board.dto.BoardQueryDto;
import com.kanban.modules.kanbancolumn.KanbanColumn;
import com.kanban.modules.kanbancolumn.KanbanColumnRepository;
import com.kanban.modules.project.ProjectRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** JAV-34 — the board's `search` filter feeds the shared full-text predicate. */
class BoardServiceTest {
  private BoardQueries boardQueries;
  private BoardService service;

  @BeforeEach
  void setUp() {
    KanbanColumnRepository columnRepository = mock(KanbanColumnRepository.class);
    ProjectRepository projectRepository = mock(ProjectRepository.class);
    boardQueries = mock(BoardQueries.class);
    KanbanColumn column = new KanbanColumn();
    column.setId(1);
    column.setName("Todo");
    column.setProjectId("UrzWUH3e");
    when(projectRepository.existsById("UrzWUH3e")).thenReturn(true);
    when(columnRepository.findByIsArchivedFalseAndProjectIdOrderByPositionAsc("UrzWUH3e")).thenReturn(List.of(column));
    when(boardQueries.countPerColumn(any())).thenReturn(Map.of());
    when(boardQueries.topTasksPerColumn(any(), anyInt())).thenReturn(List.of());
    service = new BoardService(columnRepository, projectRepository, boardQueries);
  }

  private BoardQueries.Filters filtersFor(String search) {
    BoardQueryDto query = new BoardQueryDto();
    query.search = search;
    service.getBoard("UrzWUH3e", query);
    ArgumentCaptor<BoardQueries.Filters> captor = ArgumentCaptor.forClass(BoardQueries.Filters.class);
    verify(boardQueries).countPerColumn(captor.capture());
    return captor.getValue();
  }

  @Test
  @DisplayName("whitespace-only search means no search filter")
  void blankSearchIsNoFilter() {
    assertThat(filtersFor("   ").search()).isNull();
  }

  @Test
  @DisplayName("search text reaches the query untouched (no LIKE wildcards or escaping)")
  void searchPassedThrough() {
    assertThat(filtersFor("login page").search()).isEqualTo("login page");
  }
}
