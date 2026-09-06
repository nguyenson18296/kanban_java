package com.kanban.modules.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kanban.common.api.ApiListResponse;
import com.kanban.common.exception.BadRequestException;
import com.kanban.common.exception.ConflictException;
import com.kanban.common.exception.ForbiddenException;
import com.kanban.common.exception.HttpException;
import com.kanban.common.exception.NotFoundException;
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
import com.kanban.testing.RecordingEventBus;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Port of invitation.service.spec.ts (Task 8: create / findPending / revoke; Task 9: accept + notification emit). */
class InvitationServiceTest {
  private ProjectInvitationRepository invitationRepository;
  private UserRepository userRepository;
  private ProjectAccessService projectAccessService;
  private ProjectMemberRepository memberRepository;
  private ProjectService projectService;
  private RecordingEventBus eventBus;
  private InvitationService service;

  private static CreateInvitationDto dto(String email, ProjectRole role) {
    CreateInvitationDto d = new CreateInvitationDto();
    d.email = email;
    d.role = role;
    if (role != null) {
      d.with("role");
    }
    return d;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> response(Throwable e) {
    return (Map<String, Object>) ((HttpException) e).getResponse();
  }

  private static String sha256(String raw) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @BeforeEach
  void setUp() {
    invitationRepository = mock(ProjectInvitationRepository.class);
    userRepository = mock(UserRepository.class);
    projectAccessService = mock(ProjectAccessService.class);
    memberRepository = mock(ProjectMemberRepository.class);
    projectService = mock(ProjectService.class);
    eventBus = new RecordingEventBus();
    service = new InvitationService(invitationRepository, userRepository, projectAccessService, memberRepository,
        projectService, eventBus);

    when(invitationRepository.saveAndFlush(any(ProjectInvitation.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Nested
  class Create {
    @Test
    @DisplayName("requires owner role to invite an admin")
    void requiresOwnerRoleToInviteAdmin() {
      when(projectAccessService.ensureRole(eq("proj1234"), eq("actor"), any()))
          .thenReturn(new ProjectMember("proj1234", "actor", ProjectRole.OWNER));
      when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
      when(invitationRepository.findPendingByProjectIdAndEmail(anyString(), anyString(), any(Instant.class)))
          .thenReturn(Optional.empty());

      service.create("proj1234", dto("a@b.com", ProjectRole.ADMIN), "actor");

      verify(projectAccessService).ensureRole("proj1234", "actor", ProjectRole.OWNER);
    }

    @Test
    @DisplayName("requires only admin role to invite a member")
    void requiresOnlyAdminRoleToInviteMember() {
      when(projectAccessService.ensureRole(eq("proj1234"), eq("actor"), any()))
          .thenReturn(new ProjectMember("proj1234", "actor", ProjectRole.ADMIN));
      when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
      when(invitationRepository.findPendingByProjectIdAndEmail(anyString(), anyString(), any(Instant.class)))
          .thenReturn(Optional.empty());

      service.create("proj1234", dto("a@b.com", null), "actor");

      verify(projectAccessService).ensureRole("proj1234", "actor", ProjectRole.ADMIN);
    }

    @Test
    @DisplayName("409s when the invitee is already a member")
    void conflictsWhenInviteeAlreadyMember() {
      when(projectAccessService.ensureRole(eq("proj1234"), eq("actor"), any()))
          .thenReturn(new ProjectMember("proj1234", "actor", ProjectRole.ADMIN));
      User invitee = new User("invitee", "a@b.com", "Invitee", null, null, true);
      when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(invitee));
      when(projectAccessService.getMembership("proj1234", "invitee"))
          .thenReturn(new ProjectMember("proj1234", "invitee", ProjectRole.MEMBER));

      assertThatThrownBy(() -> service.create("proj1234", dto("a@b.com", null), "actor"))
          .isInstanceOf(ConflictException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 409)
              .containsEntry("message", "User is already a member of this project"));
    }

    @Test
    @DisplayName("409s when a pending invitation already exists for the email")
    void conflictsWhenPendingInvitationExists() {
      when(projectAccessService.ensureRole(eq("proj1234"), eq("actor"), any()))
          .thenReturn(new ProjectMember("proj1234", "actor", ProjectRole.ADMIN));
      when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
      when(invitationRepository.findPendingByProjectIdAndEmail(anyString(), anyString(), any(Instant.class)))
          .thenReturn(Optional.of(new ProjectInvitation()));

      assertThatThrownBy(() -> service.create("proj1234", dto("a@b.com", null), "actor"))
          .isInstanceOf(ConflictException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 409)
              .containsEntry("message", "A pending invitation already exists for this email"));
    }

    @Test
    @DisplayName("stores only the sha256 hash and returns the raw token once")
    void storesHashAndReturnsRawTokenOnce() {
      when(projectAccessService.ensureRole(eq("proj1234"), eq("actor"), any()))
          .thenReturn(new ProjectMember("proj1234", "actor", ProjectRole.ADMIN));
      when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
      when(invitationRepository.findPendingByProjectIdAndEmail(anyString(), anyString(), any(Instant.class)))
          .thenReturn(Optional.empty());

      Map<String, Object> result = service.create("proj1234", dto("A@B.com ", null), "actor");

      String token = (String) result.get("token");
      assertThat(token).hasSize(64);

      ArgumentCaptor<ProjectInvitation> captor = ArgumentCaptor.forClass(ProjectInvitation.class);
      verify(invitationRepository, times(1)).saveAndFlush(captor.capture());
      ProjectInvitation created = captor.getValue();

      assertThat(created.getTokenHash()).isEqualTo(sha256(token));
      assertThat(created.getEmail()).isEqualTo("a@b.com");
      assertThat(created.getTokenHash()).isNotEqualTo(token);
    }

    @Test
    @DisplayName("emits PROJECT_INVITED when the invitee already has an account")
    void emitsProjectInvitedWhenInviteeExists() {
      when(projectAccessService.ensureRole(eq("proj1234"), eq("actor"), any()))
          .thenReturn(new ProjectMember("proj1234", "actor", ProjectRole.ADMIN));
      User invitee = new User("invitee", "a@b.com", "Invitee", null, null, true);
      when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(invitee));
      when(projectAccessService.getMembership("proj1234", "invitee")).thenReturn(null);
      when(invitationRepository.findPendingByProjectIdAndEmail(anyString(), anyString(), any(Instant.class)))
          .thenReturn(Optional.empty());
      User inviter = new User("actor", "actor@b.com", "Actor Name", null, "http://avatar", true);
      when(userRepository.findById("actor")).thenReturn(Optional.of(inviter));
      Project project = new Project();
      project.setId("proj1234");
      project.setName("Project One");
      when(projectService.findOneById("proj1234")).thenReturn(project);

      service.create("proj1234", dto("a@b.com", null), "actor");

      assertThat(eventBus.emittedOf(ProjectInvitedEvent.class)).hasSize(1);
      ProjectInvitedEvent event = eventBus.emittedOf(ProjectInvitedEvent.class).get(0);
      assertThat(event.actor_id()).isEqualTo("actor");
      assertThat(event.entity_type()).isEqualTo("project_invitation");
      assertThat(event.recipient_ids()).containsExactly("invitee");
      assertThat(event.payload())
          .containsEntry("project_id", "proj1234")
          .containsEntry("project_name", "Project One")
          .containsEntry("role", ProjectRole.MEMBER);
      @SuppressWarnings("unchecked")
      Map<String, Object> inviterJson = (Map<String, Object>) event.payload().get("inviter");
      assertThat(inviterJson)
          .containsEntry("id", "actor")
          .containsEntry("full_name", "Actor Name")
          .containsEntry("avatar_url", "http://avatar");
    }

    @Test
    @DisplayName("does not emit PROJECT_INVITED when the invitee has no account")
    void doesNotEmitProjectInvitedWhenInviteeMissing() {
      when(projectAccessService.ensureRole(eq("proj1234"), eq("actor"), any()))
          .thenReturn(new ProjectMember("proj1234", "actor", ProjectRole.ADMIN));
      when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
      when(invitationRepository.findPendingByProjectIdAndEmail(anyString(), anyString(), any(Instant.class)))
          .thenReturn(Optional.empty());

      service.create("proj1234", dto("a@b.com", null), "actor");

      assertThat(eventBus.emittedOf(ProjectInvitedEvent.class)).isEmpty();
    }
  }

  @Nested
  class FindPending {
    private ProjectInvitation invitation(String id, String email, User inviter) {
      ProjectInvitation i = new ProjectInvitation();
      i.setId(id);
      i.setProjectId("proj1234");
      i.setEmail(email);
      i.setRole(ProjectRole.MEMBER);
      i.setExpiresAt(Instant.now().plusSeconds(3600));
      i.setInvitedBy(inviter == null ? null : inviter.getId());
      i.setInviter(inviter);
      return i;
    }

    @Test
    @DisplayName("requires admin role")
    void requiresAdminRole() {
      when(projectAccessService.ensureRole(eq("proj1234"), eq("actor"), any()))
          .thenReturn(new ProjectMember("proj1234", "actor", ProjectRole.ADMIN));
      when(invitationRepository.findPendingByProjectId(eq("proj1234"), any(Instant.class))).thenReturn(List.of());

      service.findPending("proj1234", "actor");

      verify(projectAccessService).ensureRole("proj1234", "actor", ProjectRole.ADMIN);
    }

    @Test
    @DisplayName("runs the gate before any query, so a member below admin sees no invitations")
    void gateRunsBeforeTheQuery() {
      when(projectAccessService.ensureRole(eq("proj1234"), eq("actor"), any()))
          .thenThrow(new ForbiddenException("Requires admin role or higher"));

      assertThatThrownBy(() -> service.findPending("proj1234", "actor"))
          .isInstanceOf(ForbiddenException.class);

      verify(invitationRepository, never()).findPendingByProjectId(anyString(), any(Instant.class));
    }

    @Test
    @DisplayName("returns the list envelope with the loaded inviter embedded")
    void returnsListEnvelopeWithInviter() {
      when(projectAccessService.ensureRole(eq("proj1234"), eq("actor"), any()))
          .thenReturn(new ProjectMember("proj1234", "actor", ProjectRole.ADMIN));
      User inviter = new User("inviter-id", "boss@b.com", "Boss", null, null, true);
      when(invitationRepository.findPendingByProjectId(eq("proj1234"), any(Instant.class)))
          .thenReturn(List.of(invitation("inv1", "a@b.com", inviter), invitation("inv2", "c@d.com", null)));

      ApiListResponse<Map<String, Object>> result = service.findPending("proj1234", "actor");

      assertThat(result.status()).isEqualTo(200);
      assertThat(result.success()).isTrue();
      assertThat(result.data()).hasSize(2);

      Map<String, Object> first = result.data().get(0);
      assertThat(first).containsEntry("id", "inv1").containsEntry("email", "a@b.com");
      assertThat(first).extracting("inviter").isEqualTo(inviter.toJson());
      // token_hash / invited_by / accepted_by are never serialized
      assertThat(first).doesNotContainKeys("token_hash", "invited_by", "accepted_by");

      // the relation is requested but may be absent (inviter account deleted -> invited_by NULL)
      assertThat(result.data().get(1)).containsEntry("inviter", null);
    }
  }

  @Nested
  class Revoke {
    @Test
    @DisplayName("404s for an unknown invitation in the project")
    void notFoundForUnknownInvitation() {
      when(projectAccessService.ensureRole(eq("proj1234"), eq("actor"), any()))
          .thenReturn(new ProjectMember("proj1234", "actor", ProjectRole.ADMIN));
      when(invitationRepository.revokePending(eq("inv-uuid"), eq("proj1234"), any(Instant.class))).thenReturn(0);
      when(invitationRepository.findByIdAndProjectId("inv-uuid", "proj1234")).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.revoke("proj1234", "inv-uuid", "actor"))
          .isInstanceOf(NotFoundException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 404)
              .containsEntry("message", "Invitation not found"));
    }

    @Test
    @DisplayName("409s when the invitation was already accepted")
    void conflictsWhenAlreadyAccepted() {
      when(projectAccessService.ensureRole(eq("proj1234"), eq("actor"), any()))
          .thenReturn(new ProjectMember("proj1234", "actor", ProjectRole.ADMIN));
      // the conditional UPDATE matches nothing: accepted_at is set
      when(invitationRepository.revokePending(eq("inv-uuid"), eq("proj1234"), any(Instant.class))).thenReturn(0);
      ProjectInvitation invitation = new ProjectInvitation();
      invitation.setAcceptedAt(Instant.now());
      invitation.setAcceptedBy("invitee");
      when(invitationRepository.findByIdAndProjectId("inv-uuid", "proj1234")).thenReturn(Optional.of(invitation));

      assertThatThrownBy(() -> service.revoke("proj1234", "inv-uuid", "actor"))
          .isInstanceOf(ConflictException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 409)
              .containsEntry("message", "Invitation has already been accepted"));

      // the accept is left untouched - no merge writes the stale row back
      assertThat(invitation.getAcceptedAt()).isNotNull();
      assertThat(invitation.getAcceptedBy()).isEqualTo("invitee");
      verify(invitationRepository, never()).save(any(ProjectInvitation.class));
    }

    @Test
    @DisplayName("sets revoked_at with one conditional update, without a read-then-save")
    void revokesWithASingleConditionalUpdate() {
      when(projectAccessService.ensureRole(eq("proj1234"), eq("actor"), any()))
          .thenReturn(new ProjectMember("proj1234", "actor", ProjectRole.ADMIN));
      when(invitationRepository.revokePending(eq("inv-uuid"), eq("proj1234"), any(Instant.class))).thenReturn(1);

      service.revoke("proj1234", "inv-uuid", "actor");

      ArgumentCaptor<Instant> revokedAt = ArgumentCaptor.forClass(Instant.class);
      verify(invitationRepository, times(1)).revokePending(eq("inv-uuid"), eq("proj1234"), revokedAt.capture());
      assertThat(revokedAt.getValue()).isNotNull();
      verify(invitationRepository, never()).findByIdAndProjectId(anyString(), anyString());
      verify(invitationRepository, never()).save(any(ProjectInvitation.class));
    }

    @Test
    @DisplayName("is idempotent when the invitation was already revoked")
    void idempotentWhenAlreadyRevoked() {
      when(projectAccessService.ensureRole(eq("proj1234"), eq("actor"), any()))
          .thenReturn(new ProjectMember("proj1234", "actor", ProjectRole.ADMIN));
      when(invitationRepository.revokePending(eq("inv-uuid"), eq("proj1234"), any(Instant.class))).thenReturn(0);
      ProjectInvitation invitation = new ProjectInvitation();
      invitation.setRevokedAt(Instant.now());
      when(invitationRepository.findByIdAndProjectId("inv-uuid", "proj1234")).thenReturn(Optional.of(invitation));

      service.revoke("proj1234", "inv-uuid", "actor");

      verify(invitationRepository, never()).save(any(ProjectInvitation.class));
    }
  }

  @Nested
  class Accept {
    private static final String RAW_TOKEN = "a".repeat(64);
    private final User jane = new User("jane-id", "Jane@Example.com", "Jane", null, null, true);

    private ProjectInvitation validInvitation() {
      ProjectInvitation invitation = new ProjectInvitation();
      invitation.setId("inv-uuid");
      invitation.setProjectId("proj1234");
      invitation.setEmail("jane@example.com");
      invitation.setRole(ProjectRole.MEMBER);
      invitation.setExpiresAt(Instant.now().plusSeconds(60));
      invitation.setAcceptedAt(null);
      invitation.setRevokedAt(null);
      return invitation;
    }

    @Test
    @DisplayName("rejects an unknown token with the generic 400")
    void rejectsUnknownToken() {
      when(invitationRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.accept(RAW_TOKEN, jane))
          .isInstanceOf(BadRequestException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 400)
              .containsEntry("message", "Invalid or expired invitation"));
    }

    @Test
    @DisplayName("rejects an expired invitation")
    void rejectsExpiredInvitation() {
      ProjectInvitation invitation = validInvitation();
      invitation.setExpiresAt(Instant.now().minusMillis(1));
      when(invitationRepository.findByTokenHash(anyString())).thenReturn(Optional.of(invitation));

      assertThatThrownBy(() -> service.accept(RAW_TOKEN, jane))
          .isInstanceOf(BadRequestException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 400)
              .containsEntry("message", "Invalid or expired invitation"));
    }

    @Test
    @DisplayName("rejects a revoked invitation")
    void rejectsRevokedInvitation() {
      ProjectInvitation invitation = validInvitation();
      invitation.setRevokedAt(Instant.now());
      when(invitationRepository.findByTokenHash(anyString())).thenReturn(Optional.of(invitation));

      assertThatThrownBy(() -> service.accept(RAW_TOKEN, jane))
          .isInstanceOf(BadRequestException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 400)
              .containsEntry("message", "Invalid or expired invitation"));
    }

    @Test
    @DisplayName("rejects reuse of an accepted invitation")
    void rejectsAcceptedInvitation() {
      ProjectInvitation invitation = validInvitation();
      invitation.setAcceptedAt(Instant.now());
      when(invitationRepository.findByTokenHash(anyString())).thenReturn(Optional.of(invitation));

      assertThatThrownBy(() -> service.accept(RAW_TOKEN, jane))
          .isInstanceOf(BadRequestException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 400)
              .containsEntry("message", "Invalid or expired invitation"));
    }

    @Test
    @DisplayName("rejects a user whose email does not match, with the same generic 400")
    void rejectsEmailMismatch() {
      when(invitationRepository.findByTokenHash(anyString())).thenReturn(Optional.of(validInvitation()));
      User mallory = new User("mallory", "mallory@evil.com", "Mallory", null, null, true);

      assertThatThrownBy(() -> service.accept(RAW_TOKEN, mallory))
          .isInstanceOf(BadRequestException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 400)
              .containsEntry("message", "Invalid or expired invitation"));
    }

    @Test
    @DisplayName("409s when the accepting user is already a member")
    void conflictsWhenAlreadyMember() {
      when(invitationRepository.findByTokenHash(anyString())).thenReturn(Optional.of(validInvitation()));
      when(projectAccessService.getMembership("proj1234", "jane-id"))
          .thenReturn(new ProjectMember("proj1234", "jane-id", ProjectRole.MEMBER));

      assertThatThrownBy(() -> service.accept(RAW_TOKEN, jane))
          .isInstanceOf(ConflictException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 409)
              .containsEntry("message", "You are already a member of this project"));
    }

    @Test
    @DisplayName("looks the invitation up by sha256(token), creates the membership, marks accepted, returns the project")
    void acceptsAndJoinsProject() {
      ProjectInvitation invitation = validInvitation();
      when(invitationRepository.findByTokenHash(anyString())).thenReturn(Optional.of(invitation));
      when(projectAccessService.getMembership("proj1234", "jane-id")).thenReturn(null);
      Project project = new Project();
      project.setId("proj1234");
      project.setName("P");
      when(projectService.findOneById("proj1234")).thenReturn(project);

      Project result = service.accept(RAW_TOKEN, jane);

      verify(invitationRepository).findByTokenHash(sha256(RAW_TOKEN));
      ArgumentCaptor<ProjectMember> memberCaptor = ArgumentCaptor.forClass(ProjectMember.class);
      verify(memberRepository).save(memberCaptor.capture());
      assertThat(memberCaptor.getValue().getProjectId()).isEqualTo("proj1234");
      assertThat(memberCaptor.getValue().getUserId()).isEqualTo("jane-id");
      assertThat(memberCaptor.getValue().getRole()).isEqualTo(ProjectRole.MEMBER);
      assertThat(invitation.getAcceptedAt()).isNotNull();
      assertThat(invitation.getAcceptedBy()).isEqualTo("jane-id");
      verify(invitationRepository).save(invitation);
      assertThat(result).isSameAs(project);
    }
  }
}
