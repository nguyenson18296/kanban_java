package com.kanban.modules.invitation;

import com.kanban.common.api.ApiListResponse;
import com.kanban.common.events.EventBus;
import com.kanban.common.exception.BadRequestException;
import com.kanban.common.exception.ConflictException;
import com.kanban.common.exception.NotFoundException;
import com.kanban.common.json.Json;
import com.kanban.modules.invitation.dto.CreateInvitationDto;
import com.kanban.modules.notification.events.ProjectInvitedEvent;
import com.kanban.modules.project.Project;
import com.kanban.modules.project.ProjectAccessService;
import com.kanban.modules.project.ProjectMember;
import com.kanban.modules.project.ProjectMemberRepository;
import com.kanban.modules.project.ProjectRole;
import com.kanban.modules.project.ProjectService;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * Project invitations: create / findPending / revoke (Task 8) and accept +
 * the PROJECT_INVITED notification emit (Task 9).
 */
@Service
public class InvitationService {
  private static final Duration INVITATION_TTL = Duration.ofDays(7);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final ProjectInvitationRepository invitationRepository;
  private final UserRepository userRepository;
  private final ProjectAccessService projectAccessService;
  private final ProjectMemberRepository memberRepository;
  private final ProjectService projectService;
  private final EventBus eventBus;

  public InvitationService(ProjectInvitationRepository invitationRepository, UserRepository userRepository,
      ProjectAccessService projectAccessService, ProjectMemberRepository memberRepository,
      ProjectService projectService, EventBus eventBus) {
    this.invitationRepository = invitationRepository;
    this.userRepository = userRepository;
    this.projectAccessService = projectAccessService;
    this.memberRepository = memberRepository;
    this.projectService = projectService;
    this.eventBus = eventBus;
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

    // In-app notification when the invitee already has an account.
    if (invitee != null) {
      Project project = projectService.findOneById(projectId);
      User inviter = userRepository.findById(actorId).orElse(null);
      // Spring dispatches by ProjectInvitedEvent type to the async DB-notification and WebSocket listeners.
      eventBus.emit(new ProjectInvitedEvent(actorId, saved.getId(),
          List.of(invitee.getId()), Json.map(
              "project_id", projectId,
              "project_name", project.getName(),
              "role", role,
              "inviter", Json.map(
                  "id", actorId,
                  "full_name", inviter != null ? inviter.getFullName() : "",
                  "avatar_url", inviter != null ? inviter.getAvatarUrl() : null))));
    }

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

  /**
   * Accept an invitation by its raw token. Every invalid-token condition
   * (unknown/expired/revoked/used/wrong email) throws the SAME generic 400 so
   * this endpoint cannot be used as an oracle for token validity or invitee
   * emails. Only after that check passes is membership conflict distinguished
   * (409). Membership creation + marking the invitation accepted happen
   * atomically.
   */
  @Transactional
  public Project accept(String token, User user) {
    ProjectInvitation invitation = invitationRepository.findByTokenHash(hashToken(token)).orElse(null);
    if (invitation == null || isInvalidFor(invitation, user)) {
      throw invalidInvitation();
    }

    ProjectMember membership = projectAccessService.getMembership(invitation.getProjectId(), user.getId());
    if (membership != null) {
      throw new ConflictException(Json.map(
          "statusCode", 409,
          "message", "You are already a member of this project"));
    }

    memberRepository.save(new ProjectMember(invitation.getProjectId(), user.getId(), invitation.getRole()));
    invitation.setAcceptedAt(Instant.now());
    invitation.setAcceptedBy(user.getId());
    invitationRepository.save(invitation);

    return projectService.findOneById(invitation.getProjectId());
  }

  private static boolean isInvalidFor(ProjectInvitation invitation, User user) {
    return invitation.getRevokedAt() != null
        || invitation.getAcceptedAt() != null
        || !invitation.getExpiresAt().isAfter(Instant.now())
        || !invitation.getEmail().equals(user.getEmail().trim().toLowerCase(Locale.ROOT));
  }

  private static BadRequestException invalidInvitation() {
    return new BadRequestException(Json.map(
        "statusCode", 400,
        "message", "Invalid or expired invitation"));
  }
}
