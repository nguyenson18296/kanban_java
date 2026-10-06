package com.kanban.modules.attachment;

import com.kanban.common.json.Json;
import com.kanban.modules.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "task_attachments")
public class TaskAttachment {
  @Id
  @UuidGenerator
  @Column(columnDefinition = "uuid")
  private String id;

  @Column(name = "task_id", nullable = false, updatable = false, columnDefinition = "uuid")
  private String taskId;

  @Column(name = "uploaded_by", updatable = false, columnDefinition = "uuid")
  private String uploadedBy;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "uploaded_by", insertable = false, updatable = false)
  private User uploader;

  @Column(name = "file_name", nullable = false, length = 255)
  private String fileName;

  @Column(name = "content_type", nullable = false, length = 100)
  private String contentType;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @Column(name = "storage_key", nullable = false, updatable = false, length = 512)
  private String storageKey;

  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public TaskAttachment() {}

  public TaskAttachment(String taskId, String uploadedBy, String fileName, String contentType, long sizeBytes,
      String storageKey) {
    this.taskId = taskId;
    this.uploadedBy = uploadedBy;
    this.fileName = fileName;
    this.contentType = contentType;
    this.sizeBytes = sizeBytes;
    this.storageKey = storageKey;
  }

  /** storage_key stays hidden; every query that serializes attachments loads the uploader. */
  public Map<String, Object> toJson() {
    return Json.map(
        "id", id,
        "task_id", taskId,
        "file_name", fileName,
        "content_type", contentType,
        "size_bytes", sizeBytes,
        "uploaded_by", uploader == null ? null : uploader.toJson(),
        "created_at", createdAt);
  }

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getTaskId() { return taskId; }
  public String getUploadedBy() { return uploadedBy; }
  public User getUploader() { return uploader; }
  public void setUploader(User uploader) { this.uploader = uploader; }
  public String getFileName() { return fileName; }
  public String getContentType() { return contentType; }
  public long getSizeBytes() { return sizeBytes; }
  public String getStorageKey() { return storageKey; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
