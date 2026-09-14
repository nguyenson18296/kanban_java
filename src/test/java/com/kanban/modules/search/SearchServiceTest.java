package com.kanban.modules.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kanban.common.api.PaginatedResponse;
import com.kanban.common.api.PaginationMeta;
import com.kanban.modules.project.ProjectAccessService;
import com.kanban.modules.search.TaskSearchQueries.Hit;
import com.kanban.modules.search.dto.TaskSearchQueryDto;
import com.kanban.modules.task.Task;
import com.kanban.modules.task.TaskRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** JAV-34 — GET /search/tasks: membership scoping, ranking order, snippet safety, pagination. */
class SearchServiceTest {
  private static final List<String> PROJECTS = List.of("UrzWUH3e", "Ab3dEf9h");

  private ProjectAccessService projectAccessService;
  private TaskSearchQueries queries;
  private TaskRepository taskRepository;
  private SearchService service;

  private static Task task(String id, String title) {
    Task t = new Task();
    t.setId(id);
    t.setTitle(title);
    return t;
  }

  private static TaskSearchQueryDto query(String q, Integer page, Integer limit) {
    TaskSearchQueryDto dto = new TaskSearchQueryDto();
    dto.q = q;
    dto.page = page;
    dto.limit = limit;
    return dto;
  }

  @BeforeEach
  void setUp() {
    projectAccessService = mock(ProjectAccessService.class);
    queries = mock(TaskSearchQueries.class);
    taskRepository = mock(TaskRepository.class);
    service = new SearchService(projectAccessService, queries, taskRepository);
  }

  @Test
  @DisplayName("blank query → empty page without touching memberships or the database")
  void blankQueryIsEmptyPage() {
    for (String q : new String[] {null, "", "   "}) {
      PaginatedResponse<Map<String, Object>> result = service.searchTasks(query(q, 2, 5), "u1");
      assertThat(result.data()).isEmpty();
      assertThat(result.meta().page()).isEqualTo(2);
      assertThat(result.meta().limit()).isEqualTo(5);
      assertThat(result.meta().total()).isZero();
      assertThat(result.meta().totalPages()).isZero();
    }
    verifyNoInteractions(projectAccessService, queries, taskRepository);
  }

  @Test
  @DisplayName("caller without memberships → empty page, the search never runs")
  void noMembershipsIsEmptyPage() {
    when(projectAccessService.getProjectIdsForUser("u1")).thenReturn(List.of());

    PaginatedResponse<Map<String, Object>> result = service.searchTasks(query("login", null, null), "u1");

    assertThat(result.data()).isEmpty();
    assertThat(result.meta().page()).isEqualTo(1);
    assertThat(result.meta().limit()).isEqualTo(20);
    assertThat(result.meta().total()).isZero();
    verifyNoInteractions(queries, taskRepository);
  }

  @Test
  @DisplayName("results keep the ranked hit order and carry project_id, snippet, assignees and labels")
  void rankedOrderWithProjectAndSnippet() {
    when(projectAccessService.getProjectIdsForUser("u1")).thenReturn(PROJECTS);
    when(queries.count(PROJECTS, "login")).thenReturn(2L);
    when(queries.search(PROJECTS, "login", 20, 0)).thenReturn(List.of(
        new Hit("t2", "Ab3dEf9h", "second"),
        new Hit("t1", "UrzWUH3e", "first")));
    when(taskRepository.findByIdInForUserWithAssigneesAndLabels(List.of("t2", "t1"), "u1"))
        .thenReturn(List.of(task("t1", "Login form"), task("t2", "Login button")));

    PaginatedResponse<Map<String, Object>> result = service.searchTasks(query("login", null, null), "u1");

    assertThat(result.data()).extracting(m -> m.get("id")).containsExactly("t2", "t1");
    assertThat(result.data().get(0))
        .containsEntry("project_id", "Ab3dEf9h")
        .containsEntry("snippet", "second")
        .containsKeys("assignees", "labels");
    assertThat(result.data().get(1)).containsEntry("project_id", "UrzWUH3e").containsEntry("snippet", "first");
    assertThat(result.meta().total()).isEqualTo(2);
    assertThat(result.meta().totalPages()).isEqualTo(1);
  }

  @Test
  @DisplayName("snippet is HTML-escaped and the match markers become <mark>")
  void snippetIsSafeHtml() {
    when(projectAccessService.getProjectIdsForUser("u1")).thenReturn(PROJECTS);
    when(queries.count(anyList(), any())).thenReturn(1L);
    when(queries.search(anyList(), any(), anyInt(), anyLong())).thenReturn(List.of(new Hit("t1", "UrzWUH3e",
        TaskSearchQueries.MARK_START + "login" + TaskSearchQueries.MARK_END + " <b>page</b> & more")));
    when(taskRepository.findByIdInForUserWithAssigneesAndLabels(anyList(), any()))
        .thenReturn(List.of(task("t1", "Login")));

    PaginatedResponse<Map<String, Object>> result = service.searchTasks(query("login", null, null), "u1");

    assertThat(result.data().get(0)).containsEntry("snippet", "<mark>login</mark> &lt;b&gt;page&lt;/b&gt; &amp; more");
  }

  @Test
  @DisplayName("page and limit become the SQL offset and are echoed in meta")
  void pagination() {
    when(projectAccessService.getProjectIdsForUser("u1")).thenReturn(PROJECTS);
    when(queries.count(PROJECTS, "login")).thenReturn(25L);
    when(queries.search(PROJECTS, "login", 10, 20)).thenReturn(List.of());

    PaginatedResponse<Map<String, Object>> result = service.searchTasks(query("login", 3, 10), "u1");

    verify(queries).search(PROJECTS, "login", 10, 20);
    assertThat(result.data()).isEmpty();
    assertThat(result.meta().page()).isEqualTo(3);
    assertThat(result.meta().limit()).isEqualTo(10);
    assertThat(result.meta().total()).isEqualTo(25);
    assertThat(result.meta().totalPages()).isEqualTo(3);
    verifyNoInteractions(taskRepository);
  }

  @Test
  @DisplayName("a page past the last one returns an empty page without running the ranked query")
  void pagePastTheEndSkipsTheQuery() {
    when(projectAccessService.getProjectIdsForUser("u1")).thenReturn(PROJECTS);
    when(queries.count(PROJECTS, "login")).thenReturn(25L);

    PaginatedResponse<Map<String, Object>> result = service.searchTasks(query("login", 4, 10), "u1");

    verify(queries, never()).search(anyList(), any(), anyInt(), anyLong());
    verifyNoInteractions(taskRepository);
    assertThat(result.data()).isEmpty();
    assertThat(result.meta()).isEqualTo(PaginationMeta.of(4, 10, 25));
  }

  @Test
  @DisplayName("a huge page number does not overflow the offset")
  void hugePageDoesNotOverflow() {
    when(projectAccessService.getProjectIdsForUser("u1")).thenReturn(PROJECTS);
    when(queries.count(PROJECTS, "login")).thenReturn(200_000_000_000L);
    when(queries.search(anyList(), any(), anyInt(), anyLong())).thenReturn(List.of());

    service.searchTasks(query("login", 1_000_000_000, 100), "u1");

    verify(queries).search(PROJECTS, "login", 100, 99_999_999_900L);
  }

  @Test
  @DisplayName("the query is trimmed before it reaches the database")
  void trimsQuery() {
    when(projectAccessService.getProjectIdsForUser("u1")).thenReturn(PROJECTS);
    when(queries.count(anyList(), any())).thenReturn(1L);
    when(queries.search(anyList(), any(), anyInt(), anyLong())).thenReturn(List.of());

    service.searchTasks(query("  login page \n", null, null), "u1");

    verify(queries).count(PROJECTS, "login page");
    verify(queries).search(eq(PROJECTS), eq("login page"), anyInt(), anyLong());
  }

  @Test
  @DisplayName("the entity load is scoped to the caller's current memberships, not just the hit ids")
  void entityLoadIsAuthorizationScoped() {
    when(projectAccessService.getProjectIdsForUser("u1")).thenReturn(PROJECTS);
    when(queries.count(anyList(), any())).thenReturn(1L);
    when(queries.search(anyList(), any(), anyInt(), anyLong())).thenReturn(List.of(new Hit("t1", "UrzWUH3e", "a")));
    when(taskRepository.findByIdInForUserWithAssigneesAndLabels(List.of("t1"), "u1"))
        .thenReturn(List.of(task("t1", "Login")));

    service.searchTasks(query("login", null, null), "u1");

    verify(taskRepository).findByIdInForUserWithAssigneesAndLabels(List.of("t1"), "u1");
  }

  @Test
  @DisplayName("a hit whose task vanished, moved out of the caller's projects, or lost its membership between the two queries is skipped")
  void skipsTaskDroppedByScopedLoad() {
    when(projectAccessService.getProjectIdsForUser("u1")).thenReturn(PROJECTS);
    when(queries.count(anyList(), any())).thenReturn(2L);
    when(queries.search(anyList(), any(), anyInt(), anyLong()))
        .thenReturn(List.of(new Hit("t1", "UrzWUH3e", "a"), new Hit("t2", "UrzWUH3e", "b")));
    when(taskRepository.findByIdInForUserWithAssigneesAndLabels(anyList(), any()))
        .thenReturn(List.of(task("t2", "Login")));

    PaginatedResponse<Map<String, Object>> result = service.searchTasks(query("login", null, null), "u1");

    assertThat(result.data()).extracting(m -> m.get("id")).containsExactly("t2");
    assertThat(result.meta().total()).isEqualTo(2);
  }
}
