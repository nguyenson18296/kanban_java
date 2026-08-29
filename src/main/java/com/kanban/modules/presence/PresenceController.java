package com.kanban.modules.presence;

import com.kanban.common.validation.ValidatedQuery;
import com.kanban.modules.auth.decorators.CurrentUser;
import com.kanban.modules.auth.guards.JwtAuth;
import com.kanban.modules.presence.dto.GetPresenceQueryDto;
import com.kanban.modules.presence.dto.PresenceListResponseDto;
import com.kanban.modules.presence.dto.PresenceStateDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Presence")
@SecurityRequirement(name = "bearer")
@JwtAuth
@RestController
@RequestMapping("/presence")
public class PresenceController {
  private final PresenceService presenceService;

  public PresenceController(PresenceService presenceService) {
    this.presenceService = presenceService;
  }

  @GetMapping
  @Operation(summary = "Bulk presence lookup")
  @ApiResponse(responseCode = "200")
  public PresenceListResponseDto getMany(@ValidatedQuery GetPresenceQueryDto query) {
    return new PresenceListResponseDto(presenceService.getOnlineStates(query.userIds));
  }

  @GetMapping("/me")
  @Operation(summary = "Current user presence")
  @ApiResponse(responseCode = "200")
  public PresenceStateDto getMe(@CurrentUser("id") String userId) {
    return presenceService.getOnlineStates(List.of(userId)).get(0);
  }
}
