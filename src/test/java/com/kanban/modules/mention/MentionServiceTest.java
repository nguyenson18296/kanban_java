package com.kanban.modules.mention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kanban.modules.user.UserRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of mention.service.spec.ts */
class MentionServiceTest {
  private UserRepository userRepo;
  private MentionService service;

  @BeforeEach
  void setUp() {
    userRepo = mock(UserRepository.class);
    service = new MentionService(userRepo);
  }

  @Test
  @DisplayName("returns [] without querying when there are no mentions")
  void noMentions() {
    assertThat(service.resolveMentionedUserIds("<p>nothing</p>")).isEmpty();
    verifyNoInteractions(userRepo);
  }

  @Test
  @DisplayName("resolves active mentioned users by id")
  void resolvesActiveById() {
    when(userRepo.findActiveIdsByIdIn(List.of("u1"))).thenReturn(List.of("u1"));
    List<String> ids = service.resolveMentionedUserIds("<span data-mention-id=\"u1\">@A</span>");
    assertThat(ids).containsExactly("u1");
    // the active-only query is the Java equivalent of `andWhere('user.is_active = true')`
    verify(userRepo).findActiveIdsByIdIn(List.of("u1"));
  }

  @Test
  @DisplayName("drops excluded ids (e.g. the actor)")
  void dropsExcluded() {
    when(userRepo.findActiveIdsByIdIn(List.of("u1"))).thenReturn(List.of("u1"));
    List<String> ids = service.resolveMentionedUserIds("<span data-mention-id=\"u1\">@A</span>", List.of("u1"));
    assertThat(ids).isEmpty();
  }
}
