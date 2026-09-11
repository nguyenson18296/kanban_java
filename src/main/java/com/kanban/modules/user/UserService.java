package com.kanban.modules.user;

import com.kanban.common.api.ApiListResponse;
import com.kanban.common.exception.ConflictException;
import com.kanban.common.exception.ForbiddenException;
import com.kanban.common.exception.HttpException;
import com.kanban.common.exception.InternalServerErrorException;
import com.kanban.common.exception.NotFoundException;
import com.kanban.common.json.Json;
import com.kanban.common.util.PgErrors;
import com.kanban.modules.project.ProjectMember;
import com.kanban.modules.project.ProjectMemberRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class UserService {
  private static final Logger log = LoggerFactory.getLogger(UserService.class);

  private final UserRepository userRepository;
  private final ProjectMemberRepository projectMemberRepository;

  public UserService(UserRepository userRepository, ProjectMemberRepository projectMemberRepository) {
    this.userRepository = userRepository;
    this.projectMemberRepository = projectMemberRepository;
  }

  public List<User> findAll() {
    try {
      return userRepository.findAll();
    } catch (RuntimeException e) {
      log.error("Failed to fetch users", e);
      throw internal("Failed to fetch users", e);
    }
  }

  public User findOneById(String id) {
    try {
      return userRepository.findById(id).orElseThrow(() -> new NotFoundException(Json.map(
          "statusCode", 404,
          "message", "User with id \"" + id + "\" not found")));
    } catch (NotFoundException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to fetch user", e);
      throw internal("Failed to fetch user", e);
    }
  }

  /** The password hash is always loaded on the entity; only this path is allowed to use it. */
  public User findOneByEmailWithPassword(String email) {
    try {
      return userRepository.findByEmail(email).orElse(null);
    } catch (RuntimeException e) {
      log.error("Failed to fetch user by email with password", e);
      throw internal("Failed to fetch user", e);
    }
  }

  public User create(String email, String fullName, String passwordHash) {
    try {
      if (userRepository.findByEmail(email).isPresent()) {
        throw conflict(email);
      }
      User user = new User();
      user.setEmail(email);
      user.setFullName(fullName);
      user.setPasswordHash(passwordHash);
      return userRepository.save(user);
    } catch (ConflictException e) {
      throw e;
    } catch (RuntimeException e) {
      if (PgErrors.isCode(e, PgErrors.UNIQUE_VIOLATION)) {
        throw conflict(email);
      }
      log.error("Failed to create user", e);
      throw internal("Failed to create user", e);
    }
  }

  /**
   * {@code { data: [{ ...project, role, joined_at }], status, success }}. Only the user
   * themself may list their projects: {@code callerId} must equal {@code userId} (403).
   */
  public ApiListResponse<Map<String, Object>> findProjects(String userId, String callerId) {
    if (!userId.equals(callerId)) {
      throw new ForbiddenException(Json.map(
          "statusCode", 403,
          "message", "You can only view your own projects"));
    }
    try {
      findOneById(userId);
      List<ProjectMember> memberships = projectMemberRepository.findByUserIdWithProjectOrderByJoinedAtDesc(userId);
      List<Map<String, Object>> data = new ArrayList<>();
      for (ProjectMember m : memberships) {
        Map<String, Object> row = m.getProject().toJson(true);
        row.put("role", m.getRole());
        row.put("joined_at", m.getJoinedAt());
        data.add(row);
      }
      return ApiListResponse.ok(data);
    } catch (NotFoundException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to fetch user projects", e);
      throw internal("Failed to fetch user projects", e);
    }
  }

  public User findOneByEmail(String email) {
    try {
      return userRepository.findByEmail(email).orElseThrow(() -> new NotFoundException(Json.map(
          "statusCode", 404,
          "message", "User with email \"" + email + "\" not found")));
    } catch (NotFoundException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to fetch user by email", e);
      throw internal("Failed to fetch user", e);
    }
  }

  private static ConflictException conflict(String email) {
    return new ConflictException(Json.map(
        "statusCode", 409,
        "message", "User with email \"" + email + "\" already exists"));
  }

  private static HttpException internal(String message, Throwable cause) {
    return new InternalServerErrorException(Json.map(
        "statusCode", 500,
        "message", message,
        "error", PgErrors.message(cause)));
  }
}
