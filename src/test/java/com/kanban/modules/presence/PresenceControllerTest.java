package com.kanban.modules.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kanban.modules.presence.dto.GetPresenceQueryDto;
import com.kanban.modules.presence.dto.PresenceListResponseDto;
import com.kanban.modules.presence.dto.PresenceStateDto;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of presence.controller.spec.ts */
class PresenceControllerTest {
  private PresenceService service;
  private PresenceController controller;

  @BeforeEach
  void setUp() {
    service = mock(PresenceService.class);
    controller = new PresenceController(service);
  }

  @Test
  @DisplayName("GET /presence returns presence states for requested userIds")
  void getMany() {
    PresenceStateDto state = new PresenceStateDto("a", true, 1, "2026-06-18T00:00:00.000Z");
    when(service.getOnlineStates(List.of("a"))).thenReturn(List.of(state));
    GetPresenceQueryDto query = new GetPresenceQueryDto();
    query.userIds = List.of("a");
    PresenceListResponseDto result = controller.getMany(query);
    verify(service).getOnlineStates(List.of("a"));
    assertThat(result).isEqualTo(new PresenceListResponseDto(List.of(state)));
  }

  @Test
  @DisplayName("GET /presence/me returns the current user's presence state")
  void getMe() {
    PresenceStateDto state = new PresenceStateDto("me", true, 2, "2026-06-18T00:00:00.000Z");
    when(service.getOnlineStates(List.of("me"))).thenReturn(List.of(state));
    PresenceStateDto result = controller.getMe("me");
    verify(service).getOnlineStates(List.of("me"));
    assertThat(result).isEqualTo(state);
  }
}
