package com.kanban.common.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kanban.common.exception.BadRequestException;
import com.kanban.common.json.Json;
import com.kanban.modules.auth.dto.RegisterDto;
import com.kanban.modules.board.dto.BoardQueryDto;
import com.kanban.modules.comment.dto.CommentQueryDto;
import com.kanban.modules.comment.dto.CreateCommentDto;
import com.kanban.modules.notification.dto.NotificationQueryDto;
import com.kanban.modules.presence.dto.GetPresenceQueryDto;
import com.kanban.modules.project.dto.ManageProjectMembersDto;
import com.kanban.modules.task.TaskStatus;
import com.kanban.modules.task.dto.CreateTaskDto;
import com.kanban.modules.task.dto.UpdateTaskDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Wire-format parity of the ValidationPipe port with class-validator's messages and ordering. */
class ClassValidatorTest {
  @SuppressWarnings("unchecked")
  private static List<String> messagesOf(Throwable e) {
    Map<String, Object> body = (Map<String, Object>) ((BadRequestException) e).getResponse();
    assertThat(body).containsEntry("error", "Bad Request").containsEntry("statusCode", 400);
    return (List<String>) body.get("message");
  }

  @Test
  @DisplayName("empty register body → messages in class-validator order (bottom-up per property)")
  void registerEmpty() {
    assertThatThrownBy(() -> ClassValidator.validate(RegisterDto.class, Map.of()))
        .isInstanceOf(BadRequestException.class)
        .satisfies(e -> assertThat(messagesOf(e)).containsExactly(
            "email must be an email",
            "password must be shorter than or equal to 72 characters",
            "password must be longer than or equal to 8 characters",
            "password must be a string",
            "full_name must be shorter than or equal to 150 characters",
            "full_name must be longer than or equal to 1 characters",
            "full_name must be a string"));
  }

  @Test
  @DisplayName("forbidNonWhitelisted reports unknown properties first")
  void unknownProperty() {
    Map<String, Object> plain = Json.map("email", "a@b.co", "password", "P@ssw0rd!", "full_name", "A", "foo", 1);
    assertThatThrownBy(() -> ClassValidator.validate(RegisterDto.class, plain))
        .satisfies(e -> assertThat(messagesOf(e)).containsExactly("property foo should not exist"));
  }

  @Test
  @DisplayName("valid register body passes and tracks presence")
  void registerValid() {
    RegisterDto dto = ClassValidator.validate(RegisterDto.class,
        Json.map("email", "john@example.com", "password", "P@ssw0rd!", "full_name", "John Doe"));
    assertThat(dto.email).isEqualTo("john@example.com");
    assertThat(dto.has("password")).isTrue();
  }

  @Test
  @DisplayName("IsString rejects numbers (no implicit conversion); IsNotEmpty passes for non-empty non-strings")
  void isStringRejectsNumbers() {
    Map<String, Object> plain = Json.map("title", 123, "column_id", 1);
    assertThatThrownBy(() -> ClassValidator.validate(CreateTaskDto.class, plain))
        .satisfies(e -> assertThat(messagesOf(e)).containsExactly(
            "title must be shorter than or equal to 255 characters",
            "title must be a string"));
  }

  @Test
  @DisplayName("IsEnum message lists the enum values")
  void isEnum() {
    Map<String, Object> plain = Json.map("title", "T", "column_id", 1, "status", "nope");
    assertThatThrownBy(() -> ClassValidator.validate(CreateTaskDto.class, plain))
        .satisfies(e -> assertThat(messagesOf(e)).containsExactly(
            "status must be one of the following values: open, in_progress, in_review, done, cancelled"));
  }

  @Test
  @DisplayName("array constraints: missing user_ids yields IsArray + ArrayNotEmpty (each-UUID skipped)")
  void arrayMissing() {
    assertThatThrownBy(() -> ClassValidator.validate(ManageProjectMembersDto.class, Map.of()))
        .satisfies(e -> assertThat(messagesOf(e)).containsExactly(
            "user_ids should not be empty", "user_ids must be an array"));
    Map<String, Object> bad = Json.map("user_ids", List.of("nope"));
    assertThatThrownBy(() -> ClassValidator.validate(ManageProjectMembersDto.class, bad))
        .satisfies(e -> assertThat(messagesOf(e)).containsExactly("each value in user_ids must be a UUID"));
  }

  @Test
  @DisplayName("@Type(() => Number) coerces label_ids strings; NaN fails IsInt")
  void typeNumber() {
    CreateTaskDto ok = ClassValidator.validate(CreateTaskDto.class,
        Json.map("title", "T", "column_id", 1, "label_ids", List.of("1", 2)));
    assertThat(ok.label_ids).containsExactly(1, 2);
    Map<String, Object> bad = Json.map("title", "T", "column_id", 1, "label_ids", List.of("x"));
    assertThatThrownBy(() -> ClassValidator.validate(CreateTaskDto.class, bad))
        .satisfies(e -> assertThat(messagesOf(e)).containsExactly("each value in label_ids must be an integer number"));
  }

  @Test
  @DisplayName("due_date transform: ISO string → Date, ''/null → null (cleared), garbage → IsDate error")
  void dueDate() {
    UpdateTaskDto iso = ClassValidator.validate(UpdateTaskDto.class, Json.map("due_date", "2025-02-01T00:00:00.000Z"));
    assertThat(iso.due_date).isEqualTo(Instant.parse("2025-02-01T00:00:00Z"));
    UpdateTaskDto empty = ClassValidator.validate(UpdateTaskDto.class, Json.map("due_date", ""));
    assertThat(empty.has("due_date")).isTrue();
    assertThat(empty.due_date).isNull();
    UpdateTaskDto nul = ClassValidator.validate(UpdateTaskDto.class, Json.map("due_date", null));
    assertThat(nul.has("due_date")).isTrue();
    assertThat(nul.due_date).isNull();
    assertThatThrownBy(() -> ClassValidator.validate(UpdateTaskDto.class, Json.map("due_date", "not a date")))
        .satisfies(e -> assertThat(messagesOf(e)).containsExactly("due_date must be a Date instance"));
  }

  @Test
  @DisplayName("team_id: 0 behaves like an absent key")
  void teamIdZero() {
    UpdateTaskDto dto = ClassValidator.validate(UpdateTaskDto.class, Json.map("team_id", 0));
    assertThat(dto.has("team_id")).isFalse();
    UpdateTaskDto set = ClassValidator.validate(UpdateTaskDto.class, Json.map("team_id", 3));
    assertThat(set.has("team_id")).isTrue();
    assertThat(set.team_id).isEqualTo(3);
    UpdateTaskDto cleared = ClassValidator.validate(UpdateTaskDto.class, Json.map("team_id", null));
    assertThat(cleared.has("team_id")).isTrue();
    assertThat(cleared.team_id).isNull();
    UpdateTaskDto status = ClassValidator.validate(UpdateTaskDto.class, Json.map("status", "done"));
    assertThat(status.status).isEqualTo(TaskStatus.DONE);
  }

  @Test
  @DisplayName("query DTO defaults and coercion (board)")
  void boardQuery() {
    BoardQueryDto defaults = ClassValidator.validate(BoardQueryDto.class, Map.of());
    assertThat(defaults.tasksPerColumn).isEqualTo(50);
    BoardQueryDto parsed = ClassValidator.validate(BoardQueryDto.class, Json.map("tasksPerColumn", "10", "labelId", "3"));
    assertThat(parsed.tasksPerColumn).isEqualTo(10);
    assertThat(parsed.labelId).isEqualTo(3);
    assertThatThrownBy(() -> ClassValidator.validate(BoardQueryDto.class, Json.map("tasksPerColumn", "500")))
        .satisfies(e -> assertThat(messagesOf(e)).containsExactly("tasksPerColumn must not be greater than 200"));
    assertThatThrownBy(() -> ClassValidator.validate(BoardQueryDto.class, Json.map("tasksPerColumn", "abc")))
        .satisfies(e -> assertThat(messagesOf(e)).containsExactly(
            "tasksPerColumn must not be greater than 200",
            "tasksPerColumn must not be less than 1",
            "tasksPerColumn must be an integer number"));
  }

  @Test
  @DisplayName("comment query parseInt + notification boolean coercion")
  void commentAndNotificationQuery() {
    CommentQueryDto c = ClassValidator.validate(CommentQueryDto.class, Json.map("page", "2", "limit", "5", "sort", "ASC"));
    assertThat(c.page).isEqualTo(2);
    assertThat(c.limit).isEqualTo(5);
    assertThatThrownBy(() -> ClassValidator.validate(CommentQueryDto.class, Json.map("sort", "up")))
        .satisfies(e -> assertThat(messagesOf(e)).containsExactly("sort must be one of the following values: ASC, DESC"));
    NotificationQueryDto n = ClassValidator.validate(NotificationQueryDto.class, Json.map("is_read", "false"));
    assertThat(n.is_read).isFalse();
    assertThatThrownBy(() -> ClassValidator.validate(NotificationQueryDto.class, Json.map("is_read", "maybe")))
        .satisfies(e -> assertThat(messagesOf(e)).containsExactly("is_read must be a boolean value"));
  }

  @Test
  @DisplayName("presence userIds: comma-split + dedupe; missing → array messages")
  void presenceQuery() {
    String a = "a1b2c3d4-e5f6-4890-abcd-ef1234567890";
    String b = "b1b2c3d4-e5f6-4890-abcd-ef1234567890";
    GetPresenceQueryDto dto = ClassValidator.validate(GetPresenceQueryDto.class, Json.map("userIds", a + "," + b + "," + a));
    assertThat(dto.userIds).containsExactly(a, b);
    assertThatThrownBy(() -> ClassValidator.validate(GetPresenceQueryDto.class, Map.of()))
        .satisfies(e -> assertThat(messagesOf(e)).containsExactly(
            "userIds must contain no more than 100 elements",
            "userIds must contain at least 1 elements",
            "userIds must be an array"));
  }

  @Test
  @DisplayName("comment content is sanitized at the DTO boundary")
  void sanitizesComment() {
    CreateCommentDto dto = ClassValidator.validate(CreateCommentDto.class,
        Json.map("content", "<p onclick=\"x()\">hi <script>alert(1)</script><a href=\"javascript:x\">l</a></p>"));
    assertThat(dto.content).isEqualTo("<p>hi <a target=\"_blank\" rel=\"noopener noreferrer\">l</a></p>");
    List<Object> arr = new ArrayList<>();
    assertThatThrownBy(() -> ClassValidator.validate(CreateCommentDto.class, Json.map("content", arr)))
        .satisfies(e -> assertThat(messagesOf(e)).containsExactly("content must be a string"));
    // IsNotEmpty only rejects '' / null / undefined (class-validator semantics)
    assertThatThrownBy(() -> ClassValidator.validate(CreateCommentDto.class, Json.map("content", "")))
        .satisfies(e -> assertThat(messagesOf(e)).containsExactly("content should not be empty"));
  }
}
