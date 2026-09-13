package com.kanban.modules.search;

import com.kanban.common.api.PaginatedResponse;
import com.kanban.common.api.PaginationMeta;
import com.kanban.modules.project.ProjectAccessService;
import com.kanban.modules.search.TaskSearchQueries.Hit;
import com.kanban.modules.search.dto.TaskSearchQueryDto;
import com.kanban.modules.task.Task;
import com.kanban.modules.task.TaskRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

/**
 * Cross-project task search (JAV-34). Scoping is the caller's membership list (any role, viewer+):
 * tasks in projects the caller does not belong to are never returned or counted.
 */
@Service
public class SearchService {
  /** Loaded by {@code TaskRepository.findByIdInForUserWithAssigneesAndLabels}; matches what board cards show. */
  private static final Set<String> RESULT_RELATIONS = Set.of(Task.REL_ASSIGNEES, Task.REL_LABELS);

  private final ProjectAccessService projectAccessService;
  private final TaskSearchQueries queries;
  private final TaskRepository taskRepository;

  public SearchService(ProjectAccessService projectAccessService, TaskSearchQueries queries,
      TaskRepository taskRepository) {
    this.projectAccessService = projectAccessService;
    this.queries = queries;
    this.taskRepository = taskRepository;
  }

  public PaginatedResponse<Map<String, Object>> searchTasks(TaskSearchQueryDto query, String userId) {
    int page = query.page == null ? 1 : query.page;
    int limit = query.limit == null ? 20 : query.limit;
    String search = query.q == null ? "" : query.q.strip();
    if (search.isEmpty()) {
      return emptyPage(page, limit);
    }
    List<String> projectIds = projectAccessService.getProjectIdsForUser(userId);
    if (projectIds.isEmpty()) {
      return emptyPage(page, limit);
    }

    long total = queries.count(projectIds, search);
    long offset = (long) (page - 1) * limit; // int arithmetic overflows for large page numbers
    if (offset >= total) {
      return new PaginatedResponse<>(List.of(), PaginationMeta.of(page, limit, total));
    }
    List<Hit> hits = queries.search(projectIds, search, limit, offset);

    // The entity load is authorization-scoped too (current column → current membership), so a task
    // that was deleted, moved out of the caller's projects, or whose membership was revoked between
    // the ranked query and this statement simply comes back missing.
    Map<String, Task> tasksById = new HashMap<>();
    if (!hits.isEmpty()) {
      List<String> ids = hits.stream().map(Hit::taskId).toList();
      for (Task task : taskRepository.findByIdInForUserWithAssigneesAndLabels(ids, userId)) {
        tasksById.put(task.getId(), task);
      }
    }
    List<Map<String, Object>> items = new ArrayList<>();
    for (Hit hit : hits) {
      Task task = tasksById.get(hit.taskId());
      if (task == null) {
        continue;
      }
      Map<String, Object> json = task.toJson(RESULT_RELATIONS);
      json.put("project_id", hit.projectId());
      json.put("snippet", toHtml(hit.snippet()));
      items.add(json);
    }
    return new PaginatedResponse<>(items, PaginationMeta.of(page, limit, total));
  }

  private static PaginatedResponse<Map<String, Object>> emptyPage(int page, int limit) {
    return new PaginatedResponse<>(List.of(), PaginationMeta.of(page, limit, 0));
  }

  /**
   * Task text is plain text, so the whole headline is HTML-escaped first (the 2-arg overload keeps
   * accented characters literal); only then do the private-use markers become {@code <mark>} tags.
   * The result is an HTML fragment the frontend can render as-is.
   */
  private static String toHtml(String headline) {
    return HtmlUtils.htmlEscape(headline, "UTF-8")
        .replace(TaskSearchQueries.MARK_START, "<mark>")
        .replace(TaskSearchQueries.MARK_END, "</mark>");
  }
}
