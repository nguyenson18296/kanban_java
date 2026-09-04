package com.kanban.modules.invitation;

import com.kanban.common.api.ApiListResponse;
import com.kanban.common.exception.ConflictException;
import com.kanban.common.exception.NotFoundException;
import com.kanban.common.json.Json;
import com.kanban.modules.invitation.dto.CreateInvitationDto;
import com.kanban.modules.project.ProjectAccessService;
import com.kanban.modules.project.ProjectRole;
import com.kanban.modules.user.User;
import com.kanban.modules.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Task 8: create / findPending / revoke. Task 9 adds {@code accept(...)} to
 * this same service (and, with it, {@code ProjectService}, {@code DataSource}
 * and the notification event emitter as constructor dependencies) — not
 * injected here since nothing in this task's three methods uses them.
 */
@Service
public class InvitationService {
  private static final Duration INVITATION_TTL = Duration.ofDays(7);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final ProjectInvitationRepository invitationRepository;
  private final UserRepository userRepository;
  private final ProjectAccessService projectAccessService;

  public InvitationService(ProjectInvitationRepository invitationRepository, UserRepository userRepository,
      ProjectAccessService projectAccessService) {
    this.invitationRepository = invitationRepository;
    this.userRepository = userRepository;
    this.projectAccessService = projectAccessService;
  }

  /** {@code randomBytes(32).toString('hex')} */
  private static String generateToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  private static String hashToken(String token) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public Map<String, Object> create(String projectId, CreateInvitationDto dto, String actorId) {
    ProjectRole role = dto.role != null ? dto.role : ProjectRole.MEMBER;
    // Inviting an admin is owner-only; member/viewer invites are admin+.
    ProjectRole requiredRole = role == ProjectRole.ADMIN ? ProjectRole.OWNER : ProjectRole.ADMIN;
    projectAccessService.ensureRole(projectId, actorId, requiredRole);

    String email = dto.email.trim().toLowerCase(Locale.ROOT);

    User invitee = userRepository.findByEmail(email).orElse(null);
    if (invitee != null && projectAccessService.getMembership(projectId, invitee.getId()) != null) {
      throw new ConflictException(Json.map(
          "statusCode", 409,
          "message", "User is already a member of this project"));
    }

    if (invitationRepository.findPendingByProjectIdAndEmail(projectId, email, Instant.now()).isPresent()) {
      throw new ConflictException(Json.map(
          "statusCode", 409,
          "message", "A pending invitation already exists for this email"));
    }

    String token = generateToken();
    ProjectInvitation invitation = new ProjectInvitation();
    invitation.setProjectId(projectId);
    invitation.setEmail(email);
    invitation.setRole(role);
    invitation.setInvitedBy(actorId);
    invitation.setTokenHash(hashToken(token));
    invitation.setExpiresAt(Instant.now().plus(INVITATION_TTL));
    ProjectInvitation saved = invitationRepository.saveAndFlush(invitation);

    // Task 9 adds: in-app notification when the invitee already has an account.

    // The raw token is returned exactly once; only its hash is stored.
    Map<String, Object> json = saved.toJson(false);
    json.put("token", token);
    return json;
  }

  public ApiListResponse<Map<String, Object>> findPending(String projectId, String actorId) {
    projectAccessService.ensureRole(projectId, actorId, ProjectRole.ADMIN);
    List<ProjectInvitation> invitations = invitationRepository.findPendingByProjectId(projectId, Instant.now());
    return ApiListResponse.ok(invitations.stream().map(i -> i.toJson(true)).toList());
  }

  public void revoke(String projectId, String invitationId, String actorId) {
    projectAccessService.ensureRole(projectId, actorId, ProjectRole.ADMIN);
    if (invitationRepository.revokePending(invitationId, projectId, Instant.now()) > 0) {
      return;
    }
    // Nothing was pending to revoke: unknown invitation, already accepted, or already revoked.
    ProjectInvitation invitation = invitationRepository.findByIdAndProjectId(invitationId, projectId)
        .orElseThrow(() -> new NotFoundException(Json.map(
            "statusCode", 404,
            "message", "Invitation not found")));
    if (invitation.getAcceptedAt() != null) {
      throw new ConflictException(Json.map(
          "statusCode", 409,
          "message", "Invitation has already been accepted"));
    }
    // Already revoked — idempotent.
  }
}
