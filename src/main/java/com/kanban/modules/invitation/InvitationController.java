package com.kanban.modules.invitation;

import com.kanban.common.api.ApiListResponse;
import com.kanban.common.pipes.Param;
import com.kanban.common.validation.ValidatedBody;
import com.kanban.modules.auth.decorators.CurrentUser;
import com.kanban.modules.auth.guards.JwtAuth;
import com.kanban.modules.invitation.dto.AcceptInvitationDto;
import com.kanban.modules.invitation.dto.CreateInvitationDto;
import com.kanban.modules.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Project Invitations")
@RestController
public class InvitationController {
  private final InvitationService invitationService;

  public InvitationController(InvitationService invitationService) {
    this.invitationService = invitationService;
  }

  @PostMapping("/projects/{projectId}/invitations")
  @ResponseStatus(HttpStatus.CREATED)
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Create an invitation (admin+; owner to invite as admin). "
      + "The raw token is returned only in this response.")
  @Parameter(name = "projectId", description = "Project ID")
  @ApiResponse(responseCode = "201", description = "Invitation created (includes one-time token)")
  @ApiResponse(responseCode = "403", description = "Insufficient project role")
  @ApiResponse(responseCode = "404", description = "Project not found")
  @ApiResponse(responseCode = "409", description = "Already a member or already invited")
  public Map<String, Object> create(@Param(value = "projectId", pipe = Param.Pipe.PROJECT_ID) String projectId,
      @ValidatedBody CreateInvitationDto dto, @CurrentUser("id") String actorId) {
    return invitationService.create(projectId, dto, actorId);
  }

  @GetMapping("/projects/{projectId}/invitations")
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "List pending invitations (admin+)")
  @Parameter(name = "projectId", description = "Project ID")
  @ApiResponse(responseCode = "200", description = "List of pending invitations")
  @ApiResponse(responseCode = "403", description = "Insufficient project role")
  @ApiResponse(responseCode = "404", description = "Project not found")
  public ApiListResponse<Map<String, Object>> findPending(
      @Param(value = "projectId", pipe = Param.Pipe.PROJECT_ID) String projectId,
      @CurrentUser("id") String actorId) {
    return invitationService.findPending(projectId, actorId);
  }

  @DeleteMapping("/projects/{projectId}/invitations/{invitationId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Revoke a pending invitation (admin+)")
  @Parameter(name = "projectId", description = "Project ID")
  @Parameter(name = "invitationId", description = "Invitation UUID")
  @ApiResponse(responseCode = "204", description = "Invitation revoked")
  @ApiResponse(responseCode = "404", description = "Project or invitation not found")
  @ApiResponse(responseCode = "409", description = "Invitation already accepted")
  public void revoke(@Param(value = "projectId", pipe = Param.Pipe.PROJECT_ID) String projectId,
      @Param(value = "invitationId", pipe = Param.Pipe.UUID) String invitationId,
      @CurrentUser("id") String actorId) {
    invitationService.revoke(projectId, invitationId, actorId);
  }

  @PostMapping("/invitations/accept")
  @ResponseStatus(HttpStatus.OK)
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Accept an invitation by token (must be logged in as the invited email)")
  @ApiResponse(responseCode = "200", description = "Joined the project; returns the project")
  @ApiResponse(responseCode = "400", description = "Invalid or expired invitation")
  @ApiResponse(responseCode = "409", description = "Already a member")
  public Map<String, Object> accept(@ValidatedBody AcceptInvitationDto dto, @CurrentUser User user) {
    return invitationService.accept(dto.token, user).toJson(true);
  }
}
