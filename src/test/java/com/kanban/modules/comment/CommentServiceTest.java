package com.kanban.modules.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kanban.modules.comment.dto.CreateCommentDto;
import com.kanban.modules.mention.MentionService;
import com.kanban.modules.notification.events.BaseNotificationEvent;
import com.kanban.modules.notification.events.NotificationEvents;
import com.kanban.modules.subscription.SubscriptionService;
import com.kanban.modules.subscription.SubscriptionSource;
import com.kanban.modules.task.Task;
import com.kanban.modules.task.TaskRepository;
import com.kanban.modules.user.User;
import com.kanban.modules.user.UserRole;
import com.kanban.testing.RecordingEventBus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of comment.service.spec.ts — "CommentService (KAN-78 fan-out)" */
class CommentServiceTest {
  private CommentRepository commentRepo;
  private TaskRepository taskRepo;
  private RecordingEventBus events;
  private SubscriptionService subscription;
  private MentionService mention;
  private CommentService service;

  private List<String> recipientsOf(String eventName) {
    return ((BaseNotificationEvent) events.emittedOf(eventName).get(0)).recipient_ids();
  }

  @BeforeEach
  void setUp() {
    commentRepo = mock(CommentRepository.class);
    taskRepo = mock(TaskRepository.class);
    events = new RecordingEventBus();
    subscription = mock(SubscriptionService.class);
    mention = mock(MentionService.class);
    service = new CommentService(commentRepo, taskRepo, events, subscription, mention);

    Task task = new Task();
    task.setId("t1");
    task.setTitle("T");
    task.setTicketId("KAN-1");
    task.setCreatedBy("creator");
    when(taskRepo.findById("t1")).thenReturn(Optional.of(task));
    when(commentRepo.save(any())).thenAnswer(inv -> {
      Comment c = inv.getArgument(0);
      c.setId("c1");
      return c;
    });
    Comment saved = new Comment();
    saved.setId("c1");
    saved.setAuthor(new User("author", "a@example.com", "Author", UserRole.BACKEND_DEVELOPER, null, true));
    when(commentRepo.findByIdWithAuthor("c1")).thenReturn(Optional.of(saved));
  }

  @Test
  @DisplayName("subscribes the commenter, mentioned users, and fans out to subscribers minus author/mentioned")
  void fansOut() {
    when(mention.resolveMentionedUserIds(any(), anyList())).thenReturn(List.of("m1"));
    when(subscription.getSubscriberIds("t1")).thenReturn(List.of("author", "m1", "s1", "s2"));

    service.create("t1", "author", new CreateCommentDto("hi @m1"));

    // commenter auto-subscribed as COMMENTED
    verify(subscription).subscribe("t1", "author", SubscriptionSource.COMMENTED);
    // mentioned users auto-subscribed as MENTIONED
    verify(subscription).subscribeMany("t1", List.of("m1"), SubscriptionSource.MENTIONED);
    // COMMENT_CREATED goes to subscribers minus author minus mentioned
    assertThat(events.emittedOf(NotificationEvents.COMMENT_CREATED)).hasSize(1);
    assertThat(recipientsOf(NotificationEvents.COMMENT_CREATED)).containsExactly("s1", "s2");
    // COMMENT_MENTIONED goes to mentioned users only
    assertThat(events.emittedOf(NotificationEvents.COMMENT_MENTIONED)).hasSize(1);
    assertThat(recipientsOf(NotificationEvents.COMMENT_MENTIONED)).containsExactly("m1");
  }

  @Test
  @DisplayName("does not emit COMMENT_CREATED when the recipient set is empty")
  void emptyRecipients() {
    when(mention.resolveMentionedUserIds(any(), anyList())).thenReturn(List.of());
    when(subscription.getSubscriberIds("t1")).thenReturn(List.of("author"));

    service.create("t1", "author", new CreateCommentDto("solo note"));

    assertThat(events.emittedOf(NotificationEvents.COMMENT_CREATED)).isEmpty();
    assertThat(events.emittedOf(NotificationEvents.COMMENT_MENTIONED)).isEmpty();
  }

  @Test
  @DisplayName("still creates the comment when mention resolution fails (resilient)")
  void resilient() {
    when(mention.resolveMentionedUserIds(any(), anyList())).thenThrow(new RuntimeException("mention lookup down"));
    when(subscription.getSubscriberIds("t1")).thenReturn(List.of("author", "s1"));

    Comment result = service.create("t1", "author", new CreateCommentDto("hi @someone"));

    // Comment is returned successfully — no 500 despite the mention failure.
    assertThat(result.getId()).isEqualTo("c1");
    // No mention notification, but the comment fan-out to subscribers still runs.
    assertThat(events.emittedOf(NotificationEvents.COMMENT_MENTIONED)).isEmpty();
    assertThat(events.emittedOf(NotificationEvents.COMMENT_CREATED)).hasSize(1);
    assertThat(recipientsOf(NotificationEvents.COMMENT_CREATED)).containsExactly("s1");
  }
}
