package com.kanban.modules.attachment;

import com.kanban.common.api.PaginatedResponse;
import com.kanban.common.api.PaginationMeta;
import com.kanban.common.exception.BadRequestException;
import com.kanban.common.exception.ForbiddenException;
import com.kanban.common.exception.InternalServerErrorException;
import com.kanban.common.exception.NotFoundException;
import com.kanban.common.exception.PayloadTooLargeException;
import com.kanban.common.exception.ServiceUnavailableException;
import com.kanban.common.exception.UnsupportedMediaTypeException;
import com.kanban.common.json.Json;
import com.kanban.common.storage.FileStorage;
import com.kanban.common.storage.StorageException;
import com.kanban.common.util.PgErrors;
import com.kanban.modules.attachment.dto.AttachmentQueryDto;
import com.kanban.modules.attachment.dto.DownloadUrlDto;
import com.kanban.modules.project.ProjectAccessService;
import com.kanban.modules.project.ProjectMember;
import com.kanban.modules.project.ProjectRole;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AttachmentService {
  private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);
  private static final String STORAGE_UNAVAILABLE = "File storage is unavailable, please try again";

  private final AttachmentRepository repository;
  private final ProjectAccessService projectAccessService;
  private final FileStorage fileStorage;
  private final AttachmentProperties properties;

  public AttachmentService(AttachmentRepository repository, ProjectAccessService projectAccessService,
      FileStorage fileStorage, AttachmentProperties properties) {
    this.repository = repository;
    this.projectAccessService = projectAccessService;
    this.fileStorage = fileStorage;
    this.properties = properties;
  }

  /**
   * Stores the file first, then writes the row in its own short transaction: no pooled DB
   * connection is held while bytes go to storage. If the row cannot be written, the file is
   * deleted again (nothing would ever queue it).
   */
  public TaskAttachment upload(String taskId, MultipartFile file, String userId) {
    projectAccessService.ensureTaskRole(taskId, userId, ProjectRole.MEMBER);
    if (file == null) {
      throw new BadRequestException("File is required");
    }
    if (file.isEmpty()) {
      throw new BadRequestException("File is empty");
    }
    if (file.getSize() > properties.maxSize().toBytes()) {
      throw PayloadTooLargeException.forFileSize(properties.maxSize());
    }
    String sanitized = FileNameSanitizer.sanitize(file.getOriginalFilename());
    AllowedFileType type = AllowedFileType.detect(readHeader(file))
        .or(() -> detectMarkdown(sanitized, file))
        .orElseThrow(() ->
            new UnsupportedMediaTypeException("File type is not allowed. Allowed types: " + AllowedFileType.labels() + "."));
    String fileName = FileNameSanitizer.withExtensionOf(sanitized, type);
    // The UUID pipe accepts any casing; keep keys canonical like the ids Postgres returns.
    String key = "tasks/" + taskId.toLowerCase(Locale.ROOT) + "/" + UUID.randomUUID();

    try {
      // The MultipartFile itself, not one stream: storage re-opens it when a request is retried.
      fileStorage.put(key, file, file.getSize(), type.mimeType(), fileName);
    } catch (StorageException e) {
      log.error("Failed to store attachment for task {}", taskId, e);
      throw new ServiceUnavailableException(STORAGE_UNAVAILABLE);
    }

    TaskAttachment saved;
    try {
      saved = repository.save(new TaskAttachment(taskId, userId, fileName, type.mimeType(), file.getSize(), key));
    } catch (RuntimeException e) {
      discard(key);
      if (PgErrors.isCode(e, PgErrors.FOREIGN_KEY_VIOLATION)) {
        throw taskNotFound(taskId);
      }
      log.error("Failed to save attachment for task {}", taskId, e);
      throw new InternalServerErrorException("Failed to save attachment");
    }
    // The row is committed: from here the V6 trigger owns the file's lifecycle, so never discard
    // it. Empty means the task (and, by cascade, this row) was deleted in between.
    return repository.findByIdWithUploader(saved.getId()).orElseThrow(() -> taskNotFound(taskId));
  }

  public PaginatedResponse<Map<String, Object>> list(String taskId, AttachmentQueryDto query, String userId) {
    projectAccessService.ensureTaskRole(taskId, userId, ProjectRole.VIEWER);
    int page = query.page == null ? 1 : query.page;
    int limit = query.limit == null ? 20 : query.limit;
    Page<TaskAttachment> result = repository.findByTaskIdWithUploader(taskId,
        PageRequest.of(page - 1, limit, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
    return new PaginatedResponse<>(result.getContent().stream().map(TaskAttachment::toJson).toList(),
        PaginationMeta.of(page, limit, result.getTotalElements()));
  }

  public DownloadUrlDto downloadUrl(String taskId, String attachmentId, String userId) {
    projectAccessService.ensureTaskRole(taskId, userId, ProjectRole.VIEWER);
    TaskAttachment attachment = findOnTask(taskId, attachmentId);
    Duration ttl = properties.downloadUrlTtl();
    // Taken before signing, so the reported expiry is never later than the real one.
    Instant expiresAt = Instant.now().plus(ttl);
    try {
      return new DownloadUrlDto(fileStorage.signedDownloadUrl(attachment.getStorageKey(), ttl).toString(), expiresAt);
    } catch (StorageException e) {
      log.error("Failed to sign a download URL for attachment {}", attachmentId, e);
      throw new ServiceUnavailableException(STORAGE_UNAVAILABLE);
    }
  }

  /** Deletes the row only; its AFTER DELETE trigger queues the file for StorageDeletionJob. */
  public void delete(String taskId, String attachmentId, String userId) {
    String projectId = projectAccessService.ensureTaskRole(taskId, userId, ProjectRole.MEMBER);
    TaskAttachment attachment = findOnTask(taskId, attachmentId);
    if (!userId.equals(attachment.getUploadedBy()) && !isAdmin(projectId, userId)) {
      throw new ForbiddenException(Json.map(
          "statusCode", 403,
          "message", "Only the uploader or a project admin can delete this attachment"));
    }
    repository.deleteById(attachment.getId());
  }

  private boolean isAdmin(String projectId, String userId) {
    ProjectMember membership = projectAccessService.getMembership(projectId, userId);
    return membership != null && membership.getRole().rank() >= ProjectRole.ADMIN.rank();
  }

  /** Scoped to the task in the path: an id from another task is indistinguishable from a missing one. */
  private TaskAttachment findOnTask(String taskId, String attachmentId) {
    return repository.findByIdAndTaskId(attachmentId, taskId).orElseThrow(() -> new NotFoundException(Json.map(
        "statusCode", 404,
        "message", "Attachment with id \"" + attachmentId + "\" not found")));
  }

  private static byte[] readHeader(MultipartFile file) {
    try (InputStream in = file.getInputStream()) {
      return in.readNBytes(AllowedFileType.HEADER_LENGTH);
    } catch (IOException e) {
      log.error("Failed to read uploaded file header", e);
      throw new InternalServerErrorException("Failed to read the uploaded file");
    }
  }

  /** Reads the whole upload again (from its temp file, streamed) to confirm it is UTF-8 text. */
  private static Optional<AllowedFileType> detectMarkdown(String fileName, MultipartFile file) {
    try (InputStream in = file.getInputStream()) {
      return AllowedFileType.detectMarkdown(fileName, in);
    } catch (IOException e) {
      log.error("Failed to read uploaded file", e);
      throw new InternalServerErrorException("Failed to read the uploaded file");
    }
  }

  /** Best effort: the row was never written, so no queue entry will clean this file up. */
  private void discard(String key) {
    try {
      fileStorage.delete(key);
    } catch (StorageException e) {
      log.error("Stranded stored file {} after a failed insert", key, e);
    }
  }

  /** Byte-identical to ProjectAccessService's task-flavored 404. */
  private static NotFoundException taskNotFound(String taskId) {
    return new NotFoundException(Json.map(
        "statusCode", 404,
        "message", "Task with id \"" + taskId + "\" not found"));
  }
}
