package com.kanban.modules.kanbancolumn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kanban.common.exception.ForbiddenException;
import com.kanban.common.exception.NotFoundException;
import com.kanban.common.json.Json;
import com.kanban.modules.kanbancolumn.dto.CreateKanbanColumnDto;
import com.kanban.modules.kanbancolumn.dto.UpdateKanbanColumnDto;
import com.kanban.modules.project.ProjectAccessService;
import com.kanban.modules.project.ProjectRole;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Task 10: column routes gate on project membership via ProjectAccessService. */
class KanbanColumnServiceTest {
  private KanbanColumnRepository columnRepository;
  private ProjectAccessService projectAccessService;
  private KanbanColumnService service;

  private static CreateKanbanColumnDto createDto(String projectId, String name) {
    CreateKanbanColumnDto d = new CreateKanbanColumnDto();
    d.project_id = projectId;
    d.name = name;
    return d;
  }

  private static KanbanColumn column(int id, String name, String projectId) {
    KanbanColumn c = new KanbanColumn();
    c.setId(id);
    c.setName(name);
    c.setProjectId(projectId);
    return c;
  }

  private static NotFoundException columnNotFound(int id) {
    return new NotFoundException(Json.map("statusCode", 404, "message", "Column with id \"" + id + "\" not found"));
  }

  private static UpdateKanbanColumnDto moveDto(String targetProjectId) {
    UpdateKanbanColumnDto d = new UpdateKanbanColumnDto();
    d.project_id = targetProjectId;
    d.with("project_id");
    return d;
  }

  @BeforeEach
  void setUp() {
    columnRepository = mock(KanbanColumnRepository.class);
    projectAccessService = mock(ProjectAccessService.class);
    service = new KanbanColumnService(columnRepository, projectAccessService);
    when(columnRepository.saveAndFlush(any(KanbanColumn.class))).thenAnswer(i -> i.getArgument(0));
  }

  @Test
  @DisplayName("create requires admin on the target project, before writing")
  void createRequiresAdmin() {
    when(projectAccessService.ensureRole(eq("proj1234"), eq("actor"), any()))
        .thenThrow(new ForbiddenException("This action requires at least admin role"));

    assertThatThrownBy(() -> service.create(createDto("proj1234", "Todo"), "actor"))
        .isInstanceOf(ForbiddenException.class);
    verify(columnRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("create gates admin then persists the column")
  void createGatesThenPersists() {
    service.create(createDto("proj1234", "Todo"), "actor");

    verify(projectAccessService).ensureRole("proj1234", "actor", ProjectRole.ADMIN);
    verify(columnRepository).saveAndFlush(any(KanbanColumn.class));
  }

  @Test
  @DisplayName("findAll returns empty (no query) when the caller has no projects")
  void findAllEmptyWhenNoProjects() {
    when(projectAccessService.getProjectIdsForUser("actor")).thenReturn(List.of());

    assertThat(service.findAll("actor").data()).isEmpty();
    verify(columnRepository, never()).findByProjectIdInAndIsArchivedFalseOrderByPositionAsc(any());
    verify(columnRepository, never()).findByIsArchivedFalseOrderByPositionAsc();
  }

  @Test
  @DisplayName("findAll scopes columns to the caller's projects")
  void findAllScopesToMemberProjects() {
    when(projectAccessService.getProjectIdsForUser("actor")).thenReturn(List.of("p1", "p2"));
    when(columnRepository.findByProjectIdInAndIsArchivedFalseOrderByPositionAsc(List.of("p1", "p2")))
        .thenReturn(List.of(column(1, "Todo", "p1")));

    assertThat(service.findAll("actor").data()).hasSize(1);
    verify(columnRepository, never()).findByIsArchivedFalseOrderByPositionAsc();
  }

  @Test
  @DisplayName("findOneById gates viewer before loading the column")
  void findOneGatesViewer() {
    when(columnRepository.findById(5)).thenReturn(Optional.of(column(5, "Todo", "p1")));

    service.findOneById(5, "actor");

    verify(projectAccessService).ensureColumnRole(5, "actor", ProjectRole.VIEWER);
  }

  @Test
  @DisplayName("findOneById returns the column-flavored 404 for a non-member, before any load")
  void findOneMasks404() {
    when(projectAccessService.ensureColumnRole(eq(5), eq("actor"), any())).thenThrow(columnNotFound(5));

    assertThatThrownBy(() -> service.findOneById(5, "actor")).isInstanceOf(NotFoundException.class);
    verify(columnRepository, never()).findById(anyInt());
  }

  @Test
  @DisplayName("update requires admin on the column's project, before writing")
  void updateRequiresAdmin() {
    when(projectAccessService.ensureColumnRole(eq(5), eq("actor"), any())).thenThrow(columnNotFound(5));

    assertThatThrownBy(() -> service.update(5, new UpdateKanbanColumnDto(), "actor"))
        .isInstanceOf(NotFoundException.class);
    verify(columnRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("update gates admin then saves")
  void updateGatesAdmin() {
    when(columnRepository.findById(5)).thenReturn(Optional.of(column(5, "Todo", "p1")));

    service.update(5, new UpdateKanbanColumnDto(), "actor");

    verify(projectAccessService).ensureColumnRole(5, "actor", ProjectRole.ADMIN);
    verify(columnRepository).saveAndFlush(any(KanbanColumn.class));
  }

  @Test
  @DisplayName("update moving a column to a different project also gates admin on the target project")
  void updateMoveGatesTargetAdmin() {
    when(columnRepository.findById(5)).thenReturn(Optional.of(column(5, "Todo", "p1")));

    service.update(5, moveDto("p2"), "actor");

    verify(projectAccessService).ensureColumnRole(5, "actor", ProjectRole.ADMIN);
    verify(projectAccessService).ensureRole("p2", "actor", ProjectRole.ADMIN);
    verify(columnRepository).saveAndFlush(any(KanbanColumn.class));
  }

  @Test
  @DisplayName("update masks a move into a project the caller is not a member of (404, no write)")
  void updateMoveMasksNonMemberTarget() {
    when(columnRepository.findById(5)).thenReturn(Optional.of(column(5, "Todo", "p1")));
    when(projectAccessService.ensureRole(eq("p2"), eq("actor"), any()))
        .thenThrow(new NotFoundException(Json.map("statusCode", 404, "message", "Project with id \"p2\" not found")));

    assertThatThrownBy(() -> service.update(5, moveDto("p2"), "actor")).isInstanceOf(NotFoundException.class);
    verify(columnRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("update returns 403 (not 500) when the caller is not an admin of the target project")
  void updateMoveForbiddenNotDowngraded() {
    when(columnRepository.findById(5)).thenReturn(Optional.of(column(5, "Todo", "p1")));
    when(projectAccessService.ensureRole(eq("p2"), eq("actor"), any()))
        .thenThrow(new ForbiddenException("This action requires at least admin role"));

    assertThatThrownBy(() -> service.update(5, moveDto("p2"), "actor")).isInstanceOf(ForbiddenException.class);
    verify(columnRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("update without a project_id does not run a target-project gate")
  void updateSameProjectSkipsTargetGate() {
    when(columnRepository.findById(5)).thenReturn(Optional.of(column(5, "Todo", "p1")));
    UpdateKanbanColumnDto dto = new UpdateKanbanColumnDto();
    dto.name = "Renamed";
    dto.with("name");

    service.update(5, dto, "actor");

    verify(projectAccessService, never()).ensureRole(anyString(), anyString(), any());
    verify(columnRepository).saveAndFlush(any(KanbanColumn.class));
  }

  @Test
  @DisplayName("update with project_id equal to the current project does not re-gate")
  void updateSameTargetIdSkipsGate() {
    when(columnRepository.findById(5)).thenReturn(Optional.of(column(5, "Todo", "p1")));

    service.update(5, moveDto("p1"), "actor");

    verify(projectAccessService, never()).ensureRole(anyString(), anyString(), any());
    verify(columnRepository).saveAndFlush(any(KanbanColumn.class));
  }

  @Test
  @DisplayName("remove gates admin then deletes")
  void removeGatesAdmin() {
    when(columnRepository.findById(5)).thenReturn(Optional.of(column(5, "Todo", "p1")));

    service.remove(5, "actor");

    verify(projectAccessService).ensureColumnRole(5, "actor", ProjectRole.ADMIN);
    verify(columnRepository).deleteById(5);
  }
}
