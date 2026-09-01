package com.kanban.modules.project;

import com.kanban.common.api.ApiListResponse;
import com.kanban.common.exception.ConflictException;
import com.kanban.common.exception.ForbiddenException;
import com.kanban.common.exception.HttpException;
import com.kanban.common.exception.InternalServerErrorException;
import com.kanban.common.exception.NotFoundException;
import com.kanban.common.json.Json;
import com.kanban.common.util.PgErrors;
import com.kanban.modules.project.dto.CreateProjectDto;
import com.kanban.modules.project.dto.UpdateProjectDto;
import com.kanban.modules.team.TeamMemberRepository;
import com.kanban.modules.user.User;
import com.kanban.modules.user.UserRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ProjectService {
  private static final Logger log = LoggerFactory.getLogger(ProjectService.class);
  private static final int MAX_ID_RETRIES = 3;
  private static final Pattern DETAIL_KEY = Pattern.compile("Key \\((\\w+)\\)=\\((.+?)\\)");

  private final ProjectRepository projectRepository;
  private final ProjectMemberRepository memberRepository;
  private final UserRepository userRepository;
  private final TeamMemberRepository teamMemberRepository;
  private final TransactionTemplate transactionTemplate;
  private final ProjectAccessService projectAccessService;

  public ProjectService(ProjectRepository projectRepository, ProjectMemberRepository memberRepository,
      UserRepository userRepository, TeamMemberRepository teamMemberRepository,
      TransactionTemplate transactionTemplate, ProjectAccessService projectAccessService) {
    this.projectRepository = projectRepository;
    this.memberRepository = memberRepository;
    this.userRepository = userRepository;
    this.teamMemberRepository = teamMemberRepository;
    this.transactionTemplate = transactionTemplate;
    this.projectAccessService = projectAccessService;
  }

  String generateBaseTag(String name) {
    String cleaned = name.replaceAll("[^a-zA-Z0-9\\s]", "").trim();
    List<String> words = new ArrayList<>();
    for (String w : cleaned.split("\\s+")) {
      if (!w.isEmpty()) {
        words.add(w);
      }
    }
    if (words.size() > 1) {
      StringBuilder sb = new StringBuilder();
      for (String w : words.subList(0, Math.min(5, words.size()))) {
        sb.append(w.charAt(0));
      }
      return sb.toString().toUpperCase();
    }
    String single = words.isEmpty() ? "" : words.get(0);
    String tag = single.substring(0, Math.min(3, single.length())).toUpperCase();
    while (tag.length() < 2) {
      tag += "X";
    }
    return tag;
  }

  private String resolveUniqueTag(String baseTag) {
    Set<String> taken = new HashSet<>(projectRepository.findTagsStartingWith(baseTag + "%"));
    if (!taken.contains(baseTag)) {
      return baseTag;
    }
    for (int i = 1; i <= 99; i++) {
      String candidate = baseTag + i;
      if (!taken.contains(candidate)) {
        return candidate;
      }
    }
    throw new InternalServerErrorException(Json.map(
        "statusCode", 500,
        "message", "Unable to generate unique tag for \"" + baseTag + "\""));
  }

  public Project create(CreateProjectDto dto, String actorId) {
    String baseTag = generateBaseTag(dto.name);
    String tag = resolveUniqueTag(baseTag);
    for (int attempt = 0; attempt <= MAX_ID_RETRIES; attempt++) {
      try {
        final String currentTag = tag;
        Project saved = transactionTemplate.execute(status -> {
          Project project = new Project();
          project.setName(dto.name);
          if (dto.has("description")) {
            project.setDescription(dto.description);
          }
          project.setTag(currentTag);
          project.setCreatedBy(actorId);
          Project savedProject = projectRepository.saveAndFlush(project);
          // Auto-add creator as project owner
          if (actorId != null) {
            memberRepository.saveAndFlush(new ProjectMember(savedProject.getId(), actorId, ProjectRole.OWNER));
          }
          return savedProject;
        });
        return findOneById(saved.getId());
      } catch (HttpException e) {
        throw e;
      } catch (RuntimeException error) {
        if (PgErrors.isCode(error, PgErrors.UNIQUE_VIOLATION)) {
          String constraint = PgErrors.constraint(error);
          boolean isPkCollision = constraint != null && (constraint.contains("pkey") || constraint.startsWith("PK_"));
          boolean isTagCollision = constraint != null && constraint.contains("tag");
          if ((isPkCollision || isTagCollision) && attempt < MAX_ID_RETRIES) {
            if (isTagCollision) {
              log.warn("Tag collision on attempt {}, retrying", attempt + 1);
              tag = resolveUniqueTag(baseTag);
            } else {
              log.warn("Project ID collision on attempt {}, retrying", attempt + 1);
            }
            continue;
          }
          if (isPkCollision || isTagCollision) {
            log.error("{} collision persisted after max retries", isPkCollision ? "Project ID" : "Tag");
            throw new InternalServerErrorException(Json.map(
                "statusCode", 500,
                "message", "Failed to generate unique " + (isPkCollision ? "project ID" : "tag")));
          }
          throw new ConflictException(Json.map(
              "statusCode", 409,
              "message", "Project with name \"" + dto.name + "\" already exists",
              "error", PgErrors.message(error)));
        }
        log.error("Failed to create project", error);
        throw internal("Failed to create project", error);
      }
    }
    throw new InternalServerErrorException(Json.map("statusCode", 500, "message", "Failed to create project"));
  }

  public List<Project> findAll() {
    try {
      return projectRepository.findAllWithCreatorOrderByCreatedAtDesc();
    } catch (RuntimeException e) {
      log.error("Failed to fetch projects", e);
      throw internal("Failed to fetch projects", e);
    }
  }

  public Project findOneById(String id) {
    try {
      return projectRepository.findByIdWithCreator(id).orElseThrow(() -> projectNotFound(id));
    } catch (NotFoundException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to fetch project", e);
      throw internal("Failed to fetch project", e);
    }
  }

  public Project update(String id, UpdateProjectDto dto) {
    try {
      Project project = findOneById(id);
      if (dto.has("name")) {
        project.setName(dto.name);
      }
      if (dto.has("description")) {
        project.setDescription(dto.description);
      }
      projectRepository.saveAndFlush(project);
      return findOneById(id);
    } catch (NotFoundException | ConflictException e) {
      throw e;
    } catch (RuntimeException error) {
      if (PgErrors.isCode(error, PgErrors.UNIQUE_VIOLATION)) {
        String detail = PgErrors.detail(error) == null ? "" : PgErrors.detail(error);
        Matcher m = DETAIL_KEY.matcher(detail);
        String message = m.find()
            ? "Project with " + m.group(1) + " \"" + m.group(2) + "\" already exists"
            : "Project unique constraint violation";
        throw new ConflictException(Json.map(
            "statusCode", 409,
            "message", message,
            "error", PgErrors.message(error)));
      }
      log.error("Failed to update project", error);
      throw internal("Failed to update project", error);
    }
  }

  public void remove(String id) {
    try {
      findOneById(id);
      projectRepository.deleteById(id);
    } catch (NotFoundException e) {
      throw e;
    } catch (RuntimeException error) {
      if (PgErrors.isCode(error, PgErrors.FOREIGN_KEY_VIOLATION)) {
        throw new ConflictException(Json.map(
            "statusCode", 409,
            "message", "Cannot delete project with existing columns",
            "error", PgErrors.message(error)));
      }
      log.error("Failed to delete project", error);
      throw internal("Failed to delete project", error);
    }
  }

  // --- Project Member Management ---

  public ApiListResponse<Map<String, Object>> getMembers(String projectId) {
    try {
      ensureProjectExists(projectId);
      List<ProjectMember> members = memberRepository.findByProjectIdWithUserOrderByJoinedAtAsc(projectId);
      return ApiListResponse.ok(members.stream().map(ProjectMember::toJsonWithUser).toList());
    } catch (NotFoundException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to fetch project members", e);
      throw internal("Failed to fetch project members", e);
    }
  }

  public void addMembers(String projectId, List<String> userIds, String actorId) {
    try {
      ensureProjectExists(projectId);
      projectAccessService.ensureRole(projectId, actorId, ProjectRole.ADMIN);
      validateUsers(userIds);
      Set<String> existingIds = new HashSet<>();
      for (ProjectMember m : memberRepository.findByProjectIdAndUserIdIn(projectId, userIds)) {
        existingIds.add(m.getUserId());
      }
      List<ProjectMember> members = new ArrayList<>();
      for (String userId : userIds) {
        if (!existingIds.contains(userId)) {
          members.add(new ProjectMember(projectId, userId, ProjectRole.MEMBER));
        }
      }
      if (!members.isEmpty()) {
        memberRepository.saveAll(members);
        memberRepository.flush();
      }
    } catch (NotFoundException | ForbiddenException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to add project members", e);
      throw internal("Failed to add project members", e);
    }
  }

  public void removeMembers(String projectId, List<String> userIds, String actorId) {
    try {
      ensureProjectExists(projectId);
      projectAccessService.ensureRole(projectId, actorId, ProjectRole.ADMIN);
      // Remove from team_members first (user leaving project should leave their team too)
      teamMemberRepository.deleteByProjectIdAndUserIdIn(projectId, userIds);
      memberRepository.deleteByProjectIdAndUserIdIn(projectId, userIds);
    } catch (NotFoundException | ForbiddenException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to remove project members", e);
      throw internal("Failed to remove project members", e);
    }
  }

  /**
   * Change a member's role. Base gate: admin+. Touching owner/admin in either
   * direction (current role or new role) is owner-only. Self-change is always
   * rejected. Demoting the last owner is rejected. A same-role change is a no-op.
   *
   * <p>Runs in one transaction and locks the project row first
   * ({@code PESSIMISTIC_WRITE}), so concurrent role changes on the same project
   * serialize and the owner count in {@link #ensureNotLastOwner} cannot go stale
   * (two owners demoting each other would otherwise both pass the check). The
   * missing-project 404 matches {@code ensureRole}'s masked body, preserving
   * anti-enumeration.
   */
  @Transactional
  public ProjectMember changeMemberRole(String projectId, String targetUserId, ProjectRole newRole, String actorId) {
    projectRepository.findByIdForUpdate(projectId).orElseThrow(() -> projectNotFound(projectId));

    ProjectMember actor = projectAccessService.ensureRole(projectId, actorId, ProjectRole.ADMIN);

    if (actorId.equals(targetUserId)) {
      throw new ForbiddenException(Json.map(
          "statusCode", 403,
          "message", "You cannot change your own role"));
    }

    ProjectMember target = memberRepository.findByProjectIdAndUserId(projectId, targetUserId).orElse(null);
    if (target == null) {
      throw new NotFoundException(Json.map(
          "statusCode", 404,
          "message", "User is not a member of this project"));
    }

    // Touching owner/admin roles in either direction is owner-only.
    boolean touchesElevatedRole = isElevated(target.getRole()) || isElevated(newRole);
    ProjectRole requiredRole = touchesElevatedRole ? ProjectRole.OWNER : ProjectRole.ADMIN;
    if (actor.getRole().rank() < requiredRole.rank()) {
      throw new ForbiddenException(Json.map(
          "statusCode", 403,
          "message", "This action requires at least " + requiredRole.value() + " role"));
    }

    if (target.getRole() == ProjectRole.OWNER && newRole != ProjectRole.OWNER) {
      ensureNotLastOwner(projectId, List.of(targetUserId));
    }

    if (target.getRole() != newRole) {
      target.setRole(newRole);
      memberRepository.save(target);
    }

    return memberRepository.findByProjectIdAndUserIdWithUser(projectId, targetUserId).orElse(target);
  }

  private static boolean isElevated(ProjectRole role) {
    return role == ProjectRole.OWNER || role == ProjectRole.ADMIN;
  }

  private void ensureNotLastOwner(String projectId, List<String> leavingOwnerIds) {
    long ownersCount = memberRepository.countByProjectIdAndRole(projectId, ProjectRole.OWNER);
    if (ownersCount - leavingOwnerIds.size() < 1) {
      throw new ConflictException(Json.map(
          "statusCode", 409,
          "message", "A project must have at least one owner"));
    }
  }

  private void ensureProjectExists(String id) {
    if (!projectRepository.existsById(id)) {
      throw projectNotFound(id);
    }
  }

  private void validateUsers(List<String> userIds) {
    List<User> users = userRepository.findByIdIn(userIds);
    if (users.size() != userIds.size()) {
      Set<String> found = new HashSet<>();
      for (User u : users) {
        found.add(u.getId());
      }
      List<String> missing = userIds.stream().filter(id -> !found.contains(id)).toList();
      throw new NotFoundException(Json.map(
          "statusCode", 404,
          "message", "Users not found: " + String.join(", ", missing)));
    }
  }

  private static NotFoundException projectNotFound(String id) {
    return new NotFoundException(Json.map(
        "statusCode", 404,
        "message", "Project with id \"" + id + "\" not found"));
  }

  private static HttpException internal(String message, Throwable cause) {
    return new InternalServerErrorException(Json.map(
        "statusCode", 500,
        "message", message,
        "error", PgErrors.message(cause)));
  }
}
