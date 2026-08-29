package com.kanban.modules.project;

import com.kanban.common.exception.ForbiddenException;
import com.kanban.common.exception.NotFoundException;
import com.kanban.common.json.Json;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The single authorization gate for project-scoped resources.
 *
 * Convention: a caller with NO membership gets a 404 that is indistinguishable
 * from a nonexistent project (anti-enumeration). A member below the required
 * role gets a 403.
 */
@Service
public class ProjectAccessService {
  private final ProjectMemberRepository memberRepository;
  private final ProjectAccessQueries queries;

  public ProjectAccessService(ProjectMemberRepository memberRepository, ProjectAccessQueries queries) {
    this.memberRepository = memberRepository;
    this.queries = queries;
  }

  public ProjectMember getMembership(String projectId, String userId) {
    return memberRepository.findByProjectIdAndUserId(projectId, userId).orElse(null);
  }

  public ProjectMember ensureRole(String projectId, String userId, ProjectRole minimumRole) {
    ProjectMember membership = getMembership(projectId, userId);
    if (membership == null) {
      throw new NotFoundException(Json.map(
          "statusCode", 404,
          "message", "Project with id \"" + projectId + "\" not found"));
    }
    if (membership.getRole().rank() < minimumRole.rank()) {
      throw forbidden(minimumRole);
    }
    return membership;
  }

  public List<String> getProjectIdsForUser(String userId) {
    return memberRepository.findProjectIdsByUserId(userId);
  }

  public String getProjectIdForTask(String taskId) {
    return queries.findProjectIdForTask(taskId).orElseThrow(() -> taskNotFound(taskId));
  }

  public String getProjectIdForColumn(int columnId) {
    return queries.findProjectIdForColumn(columnId).orElseThrow(() -> columnNotFound(columnId));
  }

  /**
   * Inlined rather than delegated to ensureRole: a non-member must see a
   * task-flavored 404 (matching the unknown-task case byte-for-byte), never the
   * project-flavored message ensureRole throws.
   */
  public String ensureTaskRole(String taskId, String userId, ProjectRole minimumRole) {
    String projectId = getProjectIdForTask(taskId);
    ProjectMember membership = getMembership(projectId, userId);
    if (membership == null) {
      throw taskNotFound(taskId);
    }
    if (membership.getRole().rank() < minimumRole.rank()) {
      throw forbidden(minimumRole);
    }
    return projectId;
  }

  public String ensureColumnRole(int columnId, String userId, ProjectRole minimumRole) {
    String projectId = getProjectIdForColumn(columnId);
    ProjectMember membership = getMembership(projectId, userId);
    if (membership == null) {
      throw columnNotFound(columnId);
    }
    if (membership.getRole().rank() < minimumRole.rank()) {
      throw forbidden(minimumRole);
    }
    return projectId;
  }

  private static ForbiddenException forbidden(ProjectRole minimumRole) {
    return new ForbiddenException(Json.map(
        "statusCode", 403,
        "message", "This action requires at least " + minimumRole.value() + " role"));
  }

  private static NotFoundException taskNotFound(String taskId) {
    return new NotFoundException(Json.map(
        "statusCode", 404,
        "message", "Task with id \"" + taskId + "\" not found"));
  }

  private static NotFoundException columnNotFound(int columnId) {
    return new NotFoundException(Json.map(
        "statusCode", 404,
        "message", "Column with id \"" + columnId + "\" not found"));
  }
}
