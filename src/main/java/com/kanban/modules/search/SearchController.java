package com.kanban.modules.search;

import com.kanban.common.api.PaginatedResponse;
import com.kanban.common.validation.ValidatedQuery;
import com.kanban.modules.auth.decorators.CurrentUser;
import com.kanban.modules.auth.guards.JwtAuth;
import com.kanban.modules.search.dto.TaskSearchQueryDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Search")
@JwtAuth
@RestController
@RequestMapping("/search")
public class SearchController {
  private final SearchService searchService;

  public SearchController(SearchService searchService) {
    this.searchService = searchService;
  }

  @GetMapping("/tasks")
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Full-text search over the tasks of the caller's projects",
      description = "Matches whole words in task titles and descriptions (PostgreSQL websearch syntax: "
          + "\"quoted phrase\", -excluded, or), ranked by relevance with title matches first. Only tasks in "
          + "projects the caller is a member of are returned or counted. Each hit is the task JSON (with "
          + "assignees and labels) plus project_id and snippet — an HTML fragment with the text escaped and "
          + "matches wrapped in <mark>. A blank q returns an empty page.")
  @ApiResponse(responseCode = "200", description = "Paginated hits: { data, meta: { page, limit, total, totalPages } }")
  @ApiResponse(responseCode = "400", description = "Invalid query parameters")
  @ApiResponse(responseCode = "401", description = "Missing or invalid token")
  public PaginatedResponse<Map<String, Object>> searchTasks(@ValidatedQuery TaskSearchQueryDto query,
      @CurrentUser("id") String userId) {
    return searchService.searchTasks(query, userId);
  }
}
