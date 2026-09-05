package com.kanban.modules.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kanban.modules.kanbancolumn.KanbanColumn;
import com.kanban.modules.kanbancolumn.KanbanColumnRepository;
import com.kanban.modules.label.LabelRepository;
import com.kanban.modules.mention.MentionService;
import com.kanban.modules.notification.events.BaseNotificationEvent;
import com.kanban.modules.notification.events.TaskAssignedEvent;
import com.kanban.modules.notification.events.TaskUpdatedEvent;
import com.kanban.modules.subscription.SubscriptionService;
import com.kanban.modules.subscription.SubscriptionSource;
import com.kanban.modules.task.dto.CreateTaskDto;
import com.kanban.modules.task.dto.UpdateTaskDto;
import com.kanban.modules.user.User;
import com.kanban.modules.user.UserRepository;
import com.kanban.modules.user.UserRole;
import com.kanban.testing.RecordingEventBus;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Port of task.service.spec.ts — "TaskService (KAN-78 subscriptions)" */
class TaskServiceTest {
  private TaskRepository taskRepo;
  private UserRepository userRepo;
  private LabelRepository labelRepo;
  private KanbanColumnRepository columnRepo;
  private TaskPositionFunctions positions;
  private RecordingEventBus events;
  private SubscriptionService subscription;
  private MentionService mention;
  private TaskService service;

  private static User user(String id) {
    return new User(id, id + "@example.com", "User " + id, UserRole.BACKEND_DEVELOPER, null, true);
  }

  private static Task task(String id, String title, String ticketId, List<User> assignees) {
    Task t = new Task();
    t.setId(id);
    t.setTitle(title);
    t.setTicketId(ticketId);
    t.setAssignees(new LinkedHashSet<>(assignees));
    return t;
  }

  private List<String> recipientsOf(Class<? extends BaseNotificationEvent> type, int index) {
    return events.emittedOf(type).get(index).recipient_ids();
  }

  @BeforeEach
  void setUp() {
    taskRepo = mock(TaskRepository.class);
    userRepo = mock(UserRepository.class);
    labelRepo = mock(LabelRepository.class);
    columnRepo = mock(KanbanColumnRepository.class);
    positions = mock(TaskPositionFunctions.class);
    events = new RecordingEventBus();
    subscription = mock(SubscriptionService.class);
    mention = mock(MentionService.class);
    when(mention.resolveMentionedUserIds(any(), anyList())).thenReturn(List.of());
    when(subscription.getSubscriberIds(any())).thenReturn(List.of());
    service = new TaskService(taskRepo, userRepo, labelRepo, columnRepo, positions, events, subscription, mention);
  }

  @Nested
  class Create {
    @Test
    @DisplayName("auto-subscribes the creator and assignees and notifies assignees")
    void autoSubscribes() {
      when(columnRepo.findById(1)).thenReturn(Optional.of(new KanbanColumn()));
      when(userRepo.findByIdIn(anyList())).thenReturn(List.of(user("u1"), user("u2")));
      when(taskRepo.save(any())).thenAnswer(inv -> {
        Task t = inv.getArgument(0);
        t.setId("t1");
        return t;
      });
      when(taskRepo.findByIdWithFullRelations("t1"))
          .thenReturn(Optional.of(task("t1", "T", "KAN-1", List.of(user("u1"), user("u2")))));

      CreateTaskDto dto = new CreateTaskDto();
      dto.column_id = 1;
      dto.title = "T";
      dto.assignee_ids = List.of("u1", "u2");
      dto.with("column_id").with("title").with("assignee_ids");

      service.create(dto, "actor");

      verify(subscription).subscribe("t1", "actor", SubscriptionSource.CREATED);
      verify(subscription).subscribeMany("t1", List.of("u1", "u2"), SubscriptionSource.ASSIGNED);
      assertThat(events.emittedOf(TaskAssignedEvent.class)).hasSize(1);
      assertThat(recipientsOf(TaskAssignedEvent.class, 0)).containsExactly("u1", "u2");
    }
  }

  @Nested
  class UpdateStatusFanOut {
    private Task baseTask() {
      Task t = task("t1", "T", "KAN-1", List.of());
      t.setStatus(TaskStatus.OPEN);
      t.setPriority(TaskPriority.MEDIUM);
      t.setCreatedBy("creator");
      return t;
    }

    @Test
    @DisplayName("fans a status change out to subscribers except the actor")
    void fansOut() {
      when(taskRepo.findByIdWithFullRelations("t1")).thenReturn(Optional.of(baseTask()));
      when(subscription.getSubscriberIds("t1")).thenReturn(List.of("creator", "actor", "watcher"));

      UpdateTaskDto dto = new UpdateTaskDto();
      dto.status = TaskStatus.DONE;
      dto.with("status");
      service.update("t1", dto, "actor");

      assertThat(events.emittedOf(TaskUpdatedEvent.class)).hasSize(1);
      assertThat(recipientsOf(TaskUpdatedEvent.class, 0)).containsExactly("creator", "watcher");
    }

    @Test
    @DisplayName("does not emit TASK_UPDATED when status is unchanged")
    void unchanged() {
      when(taskRepo.findByIdWithFullRelations("t1")).thenReturn(Optional.of(baseTask()));
      when(subscription.getSubscriberIds("t1")).thenReturn(List.of("creator"));

      UpdateTaskDto dto = new UpdateTaskDto();
      dto.title = "renamed";
      dto.with("title");
      service.update("t1", dto, "actor");

      assertThat(events.emittedOf(TaskUpdatedEvent.class)).isEmpty();
    }
  }

  @Nested
  class AddAssignees {
    @Test
    @DisplayName("subscribes new assignees and notifies them")
    void subscribesAndNotifies() {
      when(taskRepo.findByIdWithFullRelations("t1")).thenReturn(Optional.of(task("t1", "T", "KAN-1", List.of())));
      when(userRepo.findByIdIn(anyList())).thenReturn(List.of(user("u1")));

      service.addAssignees("t1", List.of("u1"), "actor");

      verify(subscription).subscribeMany("t1", List.of("u1"), SubscriptionSource.ASSIGNED);
      assertThat(events.emittedOf(TaskAssignedEvent.class)).hasSize(1);
      assertThat(recipientsOf(TaskAssignedEvent.class, 0)).containsExactly("u1");
    }

    @Test
    @DisplayName("subscribes even without an actor, but emits no notification")
    void withoutActor() {
      when(taskRepo.findByIdWithFullRelations("t1")).thenReturn(Optional.of(task("t1", "T", "KAN-1", List.of())));
      when(userRepo.findByIdIn(anyList())).thenReturn(List.of(user("u1")));

      service.addAssignees("t1", List.of("u1"), null);

      verify(subscription).subscribeMany("t1", List.of("u1"), SubscriptionSource.ASSIGNED);
      assertThat(events.emittedOf(TaskAssignedEvent.class)).isEmpty();
    }
  }

  @Nested
  class DescriptionMentionResilience {
    @Test
    @DisplayName("still creates the task when description mention resolution fails")
    void createResilient() {
      when(columnRepo.findById(1)).thenReturn(Optional.of(new KanbanColumn()));
      when(taskRepo.save(any())).thenAnswer(inv -> {
        Task t = inv.getArgument(0);
        t.setId("t1");
        return t;
      });
      when(taskRepo.findByIdWithFullRelations("t1")).thenReturn(Optional.of(task("t1", "T", "KAN-1", List.of())));
      when(mention.resolveMentionedUserIds(any(), anyList())).thenThrow(new RuntimeException("mention db down"));

      CreateTaskDto dto = new CreateTaskDto();
      dto.column_id = 1;
      dto.title = "T";
      dto.description = "<p>hi @someone</p>";
      dto.with("column_id").with("title").with("description");

      Task result = service.create(dto, "actor");

      // Task is returned successfully despite the mention failure (no 500).
      assertThat(result.getId()).isEqualTo("t1");
      // Creator auto-subscribe still happened (runs before mention resolution).
      verify(subscription).subscribe("t1", "actor", SubscriptionSource.CREATED);
      verify(labelRepo, never()).findByIdIn(anyList());
    }

    @Test
    @DisplayName("still updates the task when description mention resolution fails")
    void updateResilient() {
      Task t = task("t1", "T", "KAN-1", List.of());
      t.setStatus(TaskStatus.OPEN);
      t.setPriority(TaskPriority.MEDIUM);
      t.setCreatedBy("creator");
      when(taskRepo.findByIdWithFullRelations("t1")).thenReturn(Optional.of(t));
      when(mention.resolveMentionedUserIds(any(), anyList())).thenThrow(new RuntimeException("mention db down"));

      UpdateTaskDto dto = new UpdateTaskDto();
      dto.description = "<p>hi @someone</p>";
      dto.with("description");

      Task result = service.update("t1", dto, "actor");
      assertThat(result.getId()).isEqualTo("t1");
    }
  }
}
