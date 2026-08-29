package com.kanban.modules.subscription;

import com.kanban.common.exception.NotFoundException;
import com.kanban.common.json.Json;
import com.kanban.common.util.Dates;
import com.kanban.modules.subscription.dto.SubscriptionStatusDto;
import com.kanban.modules.task.TaskRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SubscriptionService {
  private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

  private final TaskSubscriptionRepository subscriptionRepository;
  private final TaskRepository taskRepository;

  public SubscriptionService(TaskSubscriptionRepository subscriptionRepository, TaskRepository taskRepository) {
    this.subscriptionRepository = subscriptionRepository;
    this.taskRepository = taskRepository;
  }

  /** Core idempotent insert (ON CONFLICT DO NOTHING); the existing row and its source are preserved. */
  private void insertSubscription(String taskId, String userId, SubscriptionSource source) {
    subscriptionRepository.insertIgnore(taskId, userId, source.value());
  }

  /** Best-effort subscribe for auto-subscribe side effects — failures are logged and swallowed. */
  public void subscribe(String taskId, String userId, SubscriptionSource source) {
    try {
      insertSubscription(taskId, userId, source);
    } catch (RuntimeException e) {
      log.error("Failed to subscribe user {} to task {}", userId, taskId, e);
    }
  }

  /** Strict subscribe for controller-driven flows — rethrows so the API never returns a false 201. */
  public void subscribeStrict(String taskId, String userId, SubscriptionSource source) {
    insertSubscription(taskId, userId, source);
  }

  /** Idempotently subscribe many users. No-op on an empty list. Resilient (see subscribe()). */
  public void subscribeMany(String taskId, List<String> userIds, SubscriptionSource source) {
    Set<String> unique = new LinkedHashSet<>(userIds);
    if (unique.isEmpty()) {
      return;
    }
    try {
      for (String userId : unique) {
        insertSubscription(taskId, userId, source);
      }
    } catch (RuntimeException e) {
      log.error("Failed to subscribe users to task {}", taskId, e);
    }
  }

  public void unsubscribe(String taskId, String userId) {
    subscriptionRepository.deleteByTaskIdAndUserId(taskId, userId);
  }

  public boolean isSubscribed(String taskId, String userId) {
    return subscriptionRepository.existsByTaskIdAndUserId(taskId, userId);
  }

  public SubscriptionStatusDto getMyStatus(String taskId, String userId) {
    return subscriptionRepository.findByTaskIdAndUserId(taskId, userId)
        .map(sub -> new SubscriptionStatusDto(true, sub.getSource(), Dates.iso(sub.getCreatedAt())))
        .orElse(new SubscriptionStatusDto(false, null, null));
  }

  /** Subscribed user ids. Resilient: returns [] on error so a fan-out never fails the mutation. */
  public List<String> getSubscriberIds(String taskId) {
    try {
      return subscriptionRepository.findUserIdsByTaskId(taskId);
    } catch (RuntimeException e) {
      log.error("Failed to load subscribers for task {}", taskId, e);
      return List.of();
    }
  }

  public List<TaskSubscription> listSubscribers(String taskId) {
    return subscriptionRepository.findByTaskIdWithUserOrderByCreatedAtAsc(taskId);
  }

  public void ensureTaskExists(String taskId) {
    if (!taskRepository.existsById(taskId)) {
      throw new NotFoundException(Json.map(
          "statusCode", 404,
          "message", "Task with id \"" + taskId + "\" not found"));
    }
  }
}
