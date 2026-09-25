package com.kanban.modules.dependency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kanban.common.exception.ConflictException;
import com.kanban.common.exception.ForbiddenException;
import com.kanban.common.exception.HttpException;
import com.kanban.common.exception.NotFoundException;
import com.kanban.modules.activity.events.TaskActivityAction;
import com.kanban.modules.activity.events.TaskActivityEvent;
import com.kanban.modules.dependency.dto.TaskDependenciesResponseDto;
import com.kanban.modules.dependency.dto.TaskSummaryDto;
import com.kanban.modules.project.ProjectAccessService;
import com.kanban.modules.project.ProjectRole;
import com.kanban.modules.task.TaskStatus;
import com.kanban.testing.RecordingEventBus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The cycle rule is the point of this feature, so it is exercised against a real graph walk:
 * {@link FakeDependencyQueries} holds an edge map and walks it exactly as the recursive CTE does.
 * The SQL itself is unverified by tests, as every other native query in this repo is.
 */
class DependencyServiceTest {
  private static final String PROJECT = "proj1";
  private static final String USER = "u1";

  private DependencyRepository repository;
  private FakeDependencyQueries queries;
  private ProjectAccessService access;
  private RecordingEventBus eventBus;
  private DependencyService service;

  @BeforeEach
  void setUp() {
    repository = mock(DependencyRepository.class);
    queries = new FakeDependencyQueries();
    access = mock(ProjectAccessService.class);
    eventBus = new RecordingEventBus();
    service = new DependencyService(repository, queries, access, eventBus);

    when(access.ensureTaskRole(anyString(), anyString(), any())).thenReturn(PROJECT);
    when(repository.findExistingBlockers(anyString(), anyList())).thenReturn(List.of());
    when(repository.findBlockedBy(anyString())).thenReturn(List.of());
    when(repository.findBlocks(anyString())).thenReturn(List.of());
  }

  /** Walks an in-memory edge map the way the recursive CTE walks task_dependencies. */
  static class FakeDependencyQueries implements DependencyQueries {
    /** blocking task id -> tasks it blocks */
    final Map<String, Set<String>> blocks = new HashMap<>();
    final Map<String, TaskSummaryDto> tasksByProject = new HashMap<>();
    final List<String> locked = new ArrayList<>();

    void edge(String blocking, String blocked) {
      blocks.computeIfAbsent(blocking, k -> new LinkedHashSet<>()).add(blocked);
    }

    void task(String id, String ticketId) {
      tasksByProject.put(id, new TaskSummaryDto(id, ticketId, "Task " + ticketId, TaskStatus.OPEN, 1));
    }

    @Override
    public void lockProjectDependencies(String projectId) {
      locked.add(projectId);
    }

    @Override
    public List<String> findReachableFrom(String taskId, List<String> candidateIds) {
      Set<String> seen = new LinkedHashSet<>();
      List<String> frontier = new ArrayList<>(List.of(taskId));
      seen.add(taskId);
      while (!frontier.isEmpty()) {
        List<String> next = new ArrayList<>();
        for (String cur : frontier) {
          for (String child : blocks.getOrDefault(cur, Set.of())) {
            if (seen.add(child)) {
              next.add(child);
            }
          }
        }
        frontier = next;
      }
      return candidateIds.stream().filter(seen::contains).toList();
    }

    @Override
    public List<TaskSummaryDto> findTasksInProject(List<String> ids, String projectId) {
      if (!PROJECT.equals(projectId)) {
        return List.of();
      }
      return ids.stream().filter(tasksByProject::containsKey).map(tasksByProject::get).toList();
    }
  }

  private void tasksExist(String... ids) {
    for (String id : ids) {
      queries.task(id, "KAN-" + id);
    }
  }

  @Nested
  class CyclePrevention {
    @Test
    @DisplayName("rejects a link that would close a transitive cycle, and writes nothing")
    void transitiveCycle() {
      tasksExist("a", "b", "c");
      queries.edge("a", "b");
      queries.edge("b", "c");

      assertThatThrownBy(() -> service.add("a", List.of("c"), USER))
          .isInstanceOf(ConflictException.class);

      verify(repository, never()).insertIgnore(anyString(), anyString(), anyString());
      assertThat(eventBus.emittedOf(TaskActivityEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("rejects the direct back-edge A blocks B, then B blocks A")
    void directCycle() {
      tasksExist("a", "b");
      queries.edge("a", "b");

      assertThatThrownBy(() -> service.add("a", List.of("b"), USER))
          .isInstanceOf(ConflictException.class);

      verify(repository, never()).insertIgnore(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("rejects a task blocking itself")
    void selfReference() {
      tasksExist("a");

      assertThatThrownBy(() -> service.add("a", List.of("a"), USER))
          .isInstanceOf(ConflictException.class)
          .hasMessageContaining("cannot block itself");

      verify(repository, never()).insertIgnore(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("rejects the whole request when only one of several ids would cycle")
    void allOrNothing() {
      tasksExist("a", "b", "c");
      queries.edge("a", "b");

      assertThatThrownBy(() -> service.add("a", List.of("c", "b"), USER))
          .isInstanceOf(ConflictException.class);

      verify(repository, never()).insertIgnore(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("takes the per-project lock before checking, so the check and insert are guarded")
    void locksBeforeChecking() {
      tasksExist("a", "b");

      service.add("a", List.of("b"), USER);

      assertThat(queries.locked).containsExactly(PROJECT);
    }

    @Test
    @DisplayName("allows a diamond: two independent blockers of the same task")
    void diamondIsNotACycle() {
      tasksExist("a", "b", "c");
      queries.edge("b", "c");

      service.add("a", List.of("b"), USER);

      verify(repository).insertIgnore("b", "a", USER);
    }
  }

  @Nested
  class Authorization {
    @Test
    @DisplayName("gates reads at viewer and writes at member")
    void gates() {
      tasksExist("a", "b");

      service.list("a", USER);
      verify(access).ensureTaskRole("a", USER, ProjectRole.VIEWER);

      service.add("a", List.of("b"), USER);
      verify(access).ensureTaskRole("a", USER, ProjectRole.MEMBER);

      service.remove("a", List.of("b"), USER);
      verify(access, org.mockito.Mockito.times(2)).ensureTaskRole("a", USER, ProjectRole.MEMBER);
    }

    @Test
    @DisplayName("propagates the masked 404 a non-member gets, and touches nothing")
    void nonMemberIsMasked() {
      when(access.ensureTaskRole(anyString(), anyString(), any()))
          .thenThrow(new NotFoundException("Task with id \"A\" not found"));

      assertThatThrownBy(() -> service.add("a", List.of("b"), USER))
          .isInstanceOf(NotFoundException.class);

      verify(repository, never()).insertIgnore(anyString(), anyString(), anyString());
      assertThat(queries.locked).isEmpty();
    }

    @Test
    @DisplayName("propagates the 403 a viewer gets on a write")
    void viewerCannotWrite() {
      when(access.ensureTaskRole(anyString(), anyString(), any()))
          .thenThrow(new ForbiddenException("This action requires at least member role"));

      assertThatThrownBy(() -> service.add("a", List.of("b"), USER))
          .isInstanceOf(ForbiddenException.class);

      verify(repository, never()).insertIgnore(anyString(), anyString(), anyString());
    }
  }

  @Nested
  class DependencyTargets {
    @Test
    @DisplayName("404s on a blocker id that is not a task in this project")
    void crossProjectIsMasked() {
      tasksExist("a");

      assertThatThrownBy(() -> service.add("a", List.of("elsewhere"), USER))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("elsewhere");

      verify(repository, never()).insertIgnore(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("404s on an unknown blocker id with the same body as a cross-project one")
    void unknownIsMasked() {
      tasksExist("a");

      HttpException unknown = catchHttp(() -> service.add("a", List.of("00000000-0000-4000-8000-000000000000"), USER));
      HttpException cross = catchHttp(() -> service.add("a", List.of("elsewhere"), USER));

      assertThat(unknown.getStatus()).isEqualTo(404);
      assertThat(cross.getStatus()).isEqualTo(404);
    }

    private HttpException catchHttp(Runnable r) {
      try {
        r.run();
        throw new AssertionError("expected an HttpException");
      } catch (HttpException e) {
        return e;
      }
    }
  }

  @Nested
  class Writes {
    @Test
    @DisplayName("inserts one edge per blocker, directed blocker -> task")
    void insertsEdges() {
      tasksExist("a", "b", "c");

      service.add("a", List.of("b", "c"), USER);

      verify(repository).insertIgnore("b", "a", USER);
      verify(repository).insertIgnore("c", "a", USER);
    }

    @Test
    @DisplayName("records one activity listing only the edges actually created")
    void activityListsOnlyNewEdges() {
      tasksExist("a", "b", "c");
      when(repository.findExistingBlockers(anyString(), anyList())).thenReturn(List.of("b"));

      service.add("a", List.of("b", "c"), USER);

      List<TaskActivityEvent> events = eventBus.emittedOf(TaskActivityEvent.class);
      assertThat(events).hasSize(1);
      assertThat(events.get(0).action()).isEqualTo(TaskActivityAction.TASK_DEPENDENCY_ADDED);
      assertThat(events.get(0).task_id()).isEqualTo("a");
      assertThat(dependencyIds(events.get(0))).containsExactly("c");
    }

    @Test
    @DisplayName("an all-duplicate add records no activity")
    void duplicateAddIsSilent() {
      tasksExist("a", "b");
      when(repository.findExistingBlockers(anyString(), anyList())).thenReturn(List.of("b"));

      service.add("a", List.of("b"), USER);

      assertThat(eventBus.emittedOf(TaskActivityEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("deletes the requested edges and records what was removed")
    void removesEdges() {
      tasksExist("a", "b");
      when(repository.findExistingBlockers("a", List.of("b"))).thenReturn(List.of("b"));

      service.remove("a", List.of("b"), USER);

      verify(repository).deleteEdges("a", List.of("b"));
      List<TaskActivityEvent> events = eventBus.emittedOf(TaskActivityEvent.class);
      assertThat(events).hasSize(1);
      assertThat(events.get(0).action()).isEqualTo(TaskActivityAction.TASK_DEPENDENCY_REMOVED);
      assertThat(dependencyIds(events.get(0))).containsExactly("b");
    }

    @Test
    @DisplayName("a delete matching no edge issues no DELETE and records no activity")
    void noopRemoveIsSilent() {
      tasksExist("a", "b");
      // findExistingBlockers returns nothing: the task has no such blockers

      service.remove("a", List.of("b"), USER);

      verify(repository, never()).deleteEdges(anyString(), anyList());
      assertThat(eventBus.emittedOf(TaskActivityEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("removes a stale edge whose blocker has since moved to another project")
    void removesStaleCrossProjectEdge() {
      // A task can be moved into another project's column (PATCH /tasks/:id/move), which
      // leaves an edge whose blocker is no longer in this project. GET still lists it, so
      // DELETE must be able to clear it rather than 404 on the now-foreign blocker.
      tasksExist("a");
      when(repository.findExistingBlockers("a", List.of("b"))).thenReturn(List.of("b"));

      service.remove("a", List.of("b"), USER);

      verify(repository).deleteEdges("a", List.of("b"));
    }

    @Test
    @DisplayName("deduplicates repeated ids in one request")
    void deduplicates() {
      tasksExist("a", "b");

      service.add("a", List.of("b", "b"), USER);

      verify(repository, org.mockito.Mockito.times(1)).insertIgnore("b", "a", USER);
    }

    @Test
    @DisplayName("accepts a blocker id in any casing and canonicalizes it to the DB's lowercase form")
    void uppercaseIdIsCanonicalized() {
      // Postgres stores and returns uuids in lowercase, so tasks are registered lowercase; the
      // request may carry any casing (the UUID validators are case-insensitive).
      tasksExist("a", "9f1c2b3a-1d2e-4f50-9a6b-7c8d9e0f1a2b");

      service.add("a", List.of("9F1C2B3A-1D2E-4F50-9A6B-7C8D9E0F1A2B"), USER);

      verify(repository).insertIgnore("9f1c2b3a-1d2e-4f50-9a6b-7c8d9e0f1a2b", "a", USER);
    }

    @Test
    @DisplayName("two casings of the same id are a single blocker")
    void dedupesAcrossCase() {
      tasksExist("a", "b");

      service.add("a", List.of("b", "B"), USER);

      verify(repository, org.mockito.Mockito.times(1)).insertIgnore("b", "a", USER);
    }

    @SuppressWarnings("unchecked")
    private static List<String> dependencyIds(TaskActivityEvent event) {
      List<Map<String, Object>> deps = (List<Map<String, Object>>) event.payload().get("dependencies");
      return deps.stream().map(d -> String.valueOf(d.get("task_id"))).toList();
    }
  }

  @Nested
  class Reads {
    @Test
    @DisplayName("returns both directions")
    void bothDirections() {
      when(repository.findBlockedBy("a"))
          .thenReturn(List.of(new TaskSummaryDto("b", "KAN-2", "Blocker", TaskStatus.IN_PROGRESS, 3)));
      when(repository.findBlocks("a"))
          .thenReturn(List.of(new TaskSummaryDto("c", "KAN-3", "Blocked", TaskStatus.OPEN, 1)));

      TaskDependenciesResponseDto res = service.list("a", USER);

      assertThat(res.blocked_by()).extracting(TaskSummaryDto::ticket_id).containsExactly("KAN-2");
      assertThat(res.blocks()).extracting(TaskSummaryDto::ticket_id).containsExactly("KAN-3");
    }

    @Test
    @DisplayName("a successful add returns the refreshed view")
    void addReturnsView() {
      tasksExist("a", "b");
      when(repository.findBlockedBy("a"))
          .thenReturn(List.of(new TaskSummaryDto("b", "KAN-2", "Blocker", TaskStatus.OPEN, 1)));

      TaskDependenciesResponseDto res = service.add("a", List.of("b"), USER);

      assertThat(res.blocked_by()).hasSize(1);
    }
  }
}
