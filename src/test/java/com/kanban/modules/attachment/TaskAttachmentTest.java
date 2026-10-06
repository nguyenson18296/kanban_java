package com.kanban.modules.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import com.kanban.modules.user.User;
import com.kanban.modules.user.UserRole;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TaskAttachmentTest {
  private static TaskAttachment attachment() {
    TaskAttachment a = new TaskAttachment("33333333-3333-4333-8333-333333333333", "u1",
        "login-bug.png", "image/png", 482133L, "tasks/33333333-3333-4333-8333-333333333333/k");
    a.setId("55555555-5555-4555-8555-555555555555");
    a.setCreatedAt(Instant.parse("2026-09-26T10:00:00Z"));
    return a;
  }

  @Test
  @DisplayName("toJson: snake_case keys in order, storage_key hidden, uploader nested")
  void toJsonShape() {
    TaskAttachment a = attachment();
    a.setUploader(new User("u1", "a@b.co", "A", UserRole.QA, null, true));

    Map<String, Object> json = a.toJson();

    assertThat(json.keySet()).containsExactly(
        "id", "task_id", "file_name", "content_type", "size_bytes", "uploaded_by", "created_at");
    assertThat(json).doesNotContainKey("storage_key");
    assertThat(json.get("size_bytes")).isEqualTo(482133L);
    @SuppressWarnings("unchecked")
    Map<String, Object> uploader = (Map<String, Object>) json.get("uploaded_by");
    assertThat(uploader).containsEntry("id", "u1").doesNotContainKey("password_hash");
  }

  @Test
  @DisplayName("uploader account deleted → uploaded_by is null")
  void uploaderDeleted() {
    assertThat(attachment().toJson()).containsEntry("uploaded_by", null);
  }
}
