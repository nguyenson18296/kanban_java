package com.kanban.modules.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kanban.common.api.PaginatedResponse;
import com.kanban.common.api.PaginationMeta;
import com.kanban.common.exception.ForbiddenException;
import com.kanban.common.exception.HttpException;
import com.kanban.common.json.Json;
import com.kanban.modules.attachment.dto.AttachmentQueryDto;
import com.kanban.modules.attachment.dto.DownloadUrlDto;
import com.kanban.modules.project.ProjectAccessService;
import com.kanban.modules.project.ProjectMember;
import com.kanban.modules.project.ProjectRole;
import com.kanban.testing.FakeFileStorage;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

class AttachmentServiceTest {
  private static final String PROJECT = "proj1";
  private static final String TASK = "33333333-3333-4333-8333-333333333333";
  private static final String ATTACHMENT = "55555555-5555-4555-8555-555555555555";
  private static final String USER = "11111111-1111-4111-8111-111111111111";
  private static final String OTHER = "22222222-2222-4222-8222-222222222222";
  private static final byte[] PNG = bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R');
  private static final byte[] EXE = bytes('M', 'Z', 0x90, 0, 3, 0, 0, 0, 4, 0, 0, 0);
  private static final String NOT_ALLOWED = "File type is not allowed. Allowed types: PNG, JPEG, GIF, WebP, PDF, Markdown.";

  private AttachmentRepository repository;
  private ProjectAccessService access;
  private FakeFileStorage storage;
  private AttachmentService service;
  private TaskAttachment lastSaved;

  @BeforeEach
  void setUp() {
    repository = mock(AttachmentRepository.class);
    access = mock(ProjectAccessService.class);
    storage = new FakeFileStorage();
    service = serviceWithMaxSize(DataSize.ofMegabytes(10));

    when(access.ensureTaskRole(anyString(), anyString(), any())).thenReturn(PROJECT);
    when(repository.save(any(TaskAttachment.class))).thenAnswer(invocation -> {
      lastSaved = invocation.getArgument(0);
      lastSaved.setId(ATTACHMENT);
      return lastSaved;
    });
    when(repository.findByIdWithUploader(ATTACHMENT)).thenAnswer(invocation -> Optional.of(lastSaved));
  }

  private AttachmentService serviceWithMaxSize(DataSize maxSize) {
    return new AttachmentService(repository, access, storage,
        new AttachmentProperties(maxSize, Duration.ofMinutes(5)));
  }

  private static byte[] bytes(int... values) {
    byte[] out = new byte[values.length];
    for (int i = 0; i < values.length; i++) {
      out[i] = (byte) values[i];
    }
    return out;
  }

  private static MockMultipartFile file(String name, String browserType, byte[] content) {
    return new MockMultipartFile("file", name, browserType, content);
  }

  private static TaskAttachment stored(String uploadedBy) {
    TaskAttachment a = new TaskAttachment(TASK, uploadedBy, "shot.png", "image/png", 16, "tasks/" + TASK + "/k1");
    a.setId(ATTACHMENT);
    return a;
  }

  private static HttpException catchHttp(ThrowingCallable call) {
    Throwable thrown = catchThrowable(call);
    assertThat(thrown).isInstanceOf(HttpException.class);
    return (HttpException) thrown;
  }

  /** Nest createBody shape for a String message: { message, error, statusCode }. */
  private static void assertBody(ThrowingCallable call, int status, String message, String error) {
    HttpException e = catchHttp(call);
    assertThat(e.getStatus()).isEqualTo(status);
    assertThat(e.toBody()).containsExactly(entry("message", message), entry("error", error), entry("statusCode", status));
  }

  @Nested
  @DisplayName("upload")
  class Upload {
    @Test
    @DisplayName("stores the file under the task and saves a row with the detected type")
    void storesAndSaves() {
      TaskAttachment result = service.upload(TASK, file("C:\\fakepath\\shot.png", "image/png", PNG), USER);

      verify(access).ensureTaskRole(TASK, USER, ProjectRole.MEMBER);
      assertThat(storage.files).hasSize(1);
      String key = storage.files.keySet().iterator().next();
      assertThat(key).startsWith("tasks/" + TASK + "/");
      FakeFileStorage.StoredFile stored = storage.files.get(key);
      assertThat(stored.bytes()).isEqualTo(PNG);
      assertThat(stored.contentType()).isEqualTo("image/png");
      assertThat(stored.downloadName()).isEqualTo("shot.png");
      assertThat(result.getFileName()).isEqualTo("shot.png");
      assertThat(result.getContentType()).isEqualTo("image/png");
      assertThat(result.getSizeBytes()).isEqualTo(PNG.length);
      assertThat(result.getUploadedBy()).isEqualTo(USER);
      assertThat(result.getStorageKey()).isEqualTo(key);
    }

    @Test
    @DisplayName("an upper-case task id still yields a lowercase storage key")
    void lowercaseKey() {
      service.upload(TASK.toUpperCase(Locale.ROOT), file("shot.png", "image/png", PNG), USER);
      assertThat(storage.files.keySet()).allMatch(k -> k.startsWith("tasks/" + TASK + "/"));
    }

    @Test
    @DisplayName("the role gate runs before the file is looked at or stored")
    void gateFirst() {
      doThrow(new ForbiddenException(Json.map("statusCode", 403, "message", "This action requires at least member role")))
          .when(access).ensureTaskRole(TASK, USER, ProjectRole.MEMBER);

      assertThatThrownBy(() -> service.upload(TASK, null, USER)).isInstanceOf(ForbiddenException.class);
      assertThat(storage.files).isEmpty();
      verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("no file part → 400 File is required")
    void missingFile() {
      assertBody(() -> service.upload(TASK, null, USER), 400, "File is required", "Bad Request");
    }

    @Test
    @DisplayName("0-byte file → 400 File is empty")
    void emptyFile() {
      assertBody(() -> service.upload(TASK, file("shot.png", "image/png", new byte[0]), USER),
          400, "File is empty", "Bad Request");
    }

    @Test
    @DisplayName("over the limit → 413 naming the limit, nothing stored")
    void tooLarge() {
      AttachmentService small = serviceWithMaxSize(DataSize.ofBytes(8));
      assertBody(() -> small.upload(TASK, file("shot.png", "image/png", PNG), USER),
          413, "File is too large. The maximum size is 8 bytes.", "Payload Too Large");
      assertThat(storage.files).isEmpty();
    }

    @Test
    @DisplayName("an EXE renamed to .png → 415 listing the allowed types, nothing stored")
    void disguisedExecutable() {
      assertBody(() -> service.upload(TASK, file("shot.png", "image/png", EXE), USER),
          415, NOT_ALLOWED, "Unsupported Media Type");
      assertThat(storage.files).isEmpty();
    }

    @Test
    @DisplayName("a 3-byte file → 415, not a crash")
    void tinyFile() {
      assertBody(() -> service.upload(TASK, file("x.gif", "image/gif", bytes('G', 'I', 'F')), USER),
          415, NOT_ALLOWED, "Unsupported Media Type");
    }

    @Test
    @DisplayName("a PNG named report.pdf is stored as image/png and downloads as report.pdf.png")
    void typeFromBytes() {
      TaskAttachment result = service.upload(TASK, file("report.pdf", "application/pdf", PNG), USER);
      assertThat(result.getContentType()).isEqualTo("image/png");
      assertThat(result.getFileName()).isEqualTo("report.pdf.png");
    }

    @Test
    @DisplayName("a UTF-8 .md file is stored as text/markdown under its own name")
    void markdownUpload() {
      byte[] md = "# Lỗi đăng nhập\n\n1. Mở trang\n".getBytes(StandardCharsets.UTF_8);

      TaskAttachment result = service.upload(TASK, file("ghi-chu.md", "application/octet-stream", md), USER);

      assertThat(result.getContentType()).isEqualTo("text/markdown; charset=utf-8");
      assertThat(result.getFileName()).isEqualTo("ghi-chu.md");
      assertThat(storage.files.values().iterator().next().bytes()).isEqualTo(md);
    }

    @Test
    @DisplayName("a ZIP renamed to .md → 415, nothing stored")
    void binaryNamedMarkdown() {
      assertBody(() -> service.upload(TASK, file("notes.md", "text/markdown", bytes('P', 'K', 3, 4, 20, 0, 0, 0, 8, 0, 0, 0)), USER),
          415, NOT_ALLOWED, "Unsupported Media Type");
      assertThat(storage.files).isEmpty();
    }

    @Test
    @DisplayName("PDF-headed bytes named report.bat never download as a .bat")
    void polyglotGetsDetectedExtension() {
      byte[] pdfThenCommands = "%PDF-1.7\r\nstart calc.exe\r\n".getBytes(StandardCharsets.US_ASCII);

      TaskAttachment result = service.upload(TASK, file("report.bat", "application/pdf", pdfThenCommands), USER);

      assertThat(result.getFileName()).isEqualTo("report.bat.pdf");
      assertThat(storage.files.values().iterator().next().downloadName()).isEqualTo("report.bat.pdf");
    }

    @Test
    @DisplayName("storage failure → 503 with no storage details in the body; no row")
    void storageDown() {
      storage.failPut = true;
      HttpException e = catchHttp(() -> service.upload(TASK, file("shot.png", "image/png", PNG), USER));
      assertThat(e.getStatus()).isEqualTo(503);
      assertThat(e.toBody()).containsExactly(
          entry("message", "File storage is unavailable, please try again"),
          entry("error", "Service Unavailable"),
          entry("statusCode", 503));
      verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("row insert fails → the stored file is deleted and a generic 500 is thrown")
    void insertFailsCleansUp() {
      doThrow(new DataAccessResourceFailureException("connection reset by db-host-1"))
          .when(repository).save(any(TaskAttachment.class));

      assertBody(() -> service.upload(TASK, file("shot.png", "image/png", PNG), USER),
          500, "Failed to save attachment", "Internal Server Error");
      assertThat(storage.files).isEmpty();
      assertThat(storage.deleted).hasSize(1);
    }

    @Test
    @DisplayName("task deleted mid-upload (FK violation) → file deleted, masked task 404")
    void taskDeletedMidUpload() {
      doThrow(new DataIntegrityViolationException("fk", new SQLException("violates foreign key", "23503")))
          .when(repository).save(any(TaskAttachment.class));

      HttpException e = catchHttp(() -> service.upload(TASK, file("shot.png", "image/png", PNG), USER));
      assertThat(e.getStatus()).isEqualTo(404);
      assertThat(e.toBody()).containsExactly(
          entry("statusCode", 404), entry("message", "Task with id \"" + TASK + "\" not found"));
      assertThat(storage.files).isEmpty();
    }
  }

  @Nested
  @DisplayName("upload after the row is committed")
  class AfterCommit {
    @Test
    @DisplayName("re-fetch fails → the stored file is kept (its row exists) and the error propagates")
    void refetchFailsKeepsFile() {
      doThrow(new DataAccessResourceFailureException("Connection is not available, request timed out"))
          .when(repository).findByIdWithUploader(ATTACHMENT);

      assertThatThrownBy(() -> service.upload(TASK, file("shot.png", "image/png", PNG), USER))
          .isInstanceOf(DataAccessResourceFailureException.class);
      assertThat(storage.files).hasSize(1);
      assertThat(storage.deleted).isEmpty();
    }

    @Test
    @DisplayName("task deleted right after the insert → masked task 404; the cascade trigger owns the file")
    void taskDeletedAfterInsert() {
      doReturn(Optional.empty()).when(repository).findByIdWithUploader(ATTACHMENT);

      HttpException e = catchHttp(() -> service.upload(TASK, file("shot.png", "image/png", PNG), USER));
      assertThat(e.getStatus()).isEqualTo(404);
      assertThat(e.toBody()).containsExactly(
          entry("statusCode", 404), entry("message", "Task with id \"" + TASK + "\" not found"));
      assertThat(storage.deleted).isEmpty();
    }
  }

  @Nested
  @DisplayName("list")
  class ListAttachments {
    @Test
    @DisplayName("viewer gate; newest first with an id tie-break; page/limit mapped to the PageRequest")
    void listsPage() {
      when(repository.findByTaskIdWithUploader(eq(TASK), any(Pageable.class)))
          .thenReturn(new PageImpl<>(List.of(stored(USER)), PageRequest.of(1, 5), 6));
      AttachmentQueryDto query = new AttachmentQueryDto();
      query.page = 2;
      query.limit = 5;

      PaginatedResponse<Map<String, Object>> result = service.list(TASK, query, USER);

      verify(access).ensureTaskRole(TASK, USER, ProjectRole.VIEWER);
      ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
      verify(repository).findByTaskIdWithUploader(eq(TASK), pageable.capture());
      assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
      assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
      assertThat(pageable.getValue().getSort()).containsExactly(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
      assertThat(result.data()).extracting(m -> m.get("id")).containsExactly(ATTACHMENT);
      assertThat(result.meta()).isEqualTo(new PaginationMeta(2, 5, 6, 2));
    }
  }

  @Nested
  @DisplayName("downloadUrl")
  class Download {
    @Test
    @DisplayName("viewer gate; returns the signed URL and an expiry one TTL from now")
    void signsUrl() {
      when(repository.findByIdAndTaskId(ATTACHMENT, TASK)).thenReturn(Optional.of(stored(OTHER)));
      Instant before = Instant.now();

      DownloadUrlDto result = service.downloadUrl(TASK, ATTACHMENT, USER);

      verify(access).ensureTaskRole(TASK, USER, ProjectRole.VIEWER);
      assertThat(result.url()).isEqualTo("https://storage.test/tasks/" + TASK + "/k1?ttl=300");
      assertThat(result.expires_at())
          .isBetween(before.plus(Duration.ofMinutes(5)), Instant.now().plus(Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("an attachment id from another task → 404, nothing signed")
    void crossTaskIdOnDownload() {
      when(repository.findByIdAndTaskId(ATTACHMENT, TASK)).thenReturn(Optional.empty());

      HttpException e = catchHttp(() -> service.downloadUrl(TASK, ATTACHMENT, USER));
      assertThat(e.getStatus()).isEqualTo(404);
      assertThat(e.toBody()).containsExactly(
          entry("statusCode", 404), entry("message", "Attachment with id \"" + ATTACHMENT + "\" not found"));
      assertThat(storage.signed).isEmpty();
    }

    @Test
    @DisplayName("signing fails → 503")
    void signingFails() {
      when(repository.findByIdAndTaskId(ATTACHMENT, TASK)).thenReturn(Optional.of(stored(OTHER)));
      storage.failSign = true;
      assertBody(() -> service.downloadUrl(TASK, ATTACHMENT, USER),
          503, "File storage is unavailable, please try again", "Service Unavailable");
    }
  }

  @Nested
  @DisplayName("delete")
  class Delete {
    private static final Map<String, Object> ONLY_UPLOADER_OR_ADMIN = Json.map(
        "statusCode", 403, "message", "Only the uploader or a project admin can delete this attachment");

    @Test
    @DisplayName("the uploader deletes the row; storage is left to the trigger + StorageDeletionJob")
    void uploaderDeletes() {
      when(repository.findByIdAndTaskId(ATTACHMENT, TASK)).thenReturn(Optional.of(stored(USER)));

      service.delete(TASK, ATTACHMENT, USER);

      verify(access).ensureTaskRole(TASK, USER, ProjectRole.MEMBER);
      verify(repository).deleteById(ATTACHMENT);
      assertThat(storage.deleted).isEmpty();
    }

    @Test
    @DisplayName("another member → 403, nothing deleted")
    void otherMemberForbidden() {
      when(repository.findByIdAndTaskId(ATTACHMENT, TASK)).thenReturn(Optional.of(stored(OTHER)));
      when(access.getMembership(PROJECT, USER)).thenReturn(new ProjectMember(PROJECT, USER, ProjectRole.MEMBER));

      HttpException e = catchHttp(() -> service.delete(TASK, ATTACHMENT, USER));
      assertThat(e.getStatus()).isEqualTo(403);
      assertThat(e.toBody()).isEqualTo(ONLY_UPLOADER_OR_ADMIN);
      verify(repository, never()).deleteById(any());
    }

    @ParameterizedTest
    @EnumSource(value = ProjectRole.class, names = {"ADMIN", "OWNER"})
    @DisplayName("admins and owners delete anyone's file")
    void adminOrOwnerDeletes(ProjectRole role) {
      when(repository.findByIdAndTaskId(ATTACHMENT, TASK)).thenReturn(Optional.of(stored(OTHER)));
      when(access.getMembership(PROJECT, USER)).thenReturn(new ProjectMember(PROJECT, USER, role));

      service.delete(TASK, ATTACHMENT, USER);

      verify(repository).deleteById(ATTACHMENT);
    }

    @Test
    @DisplayName("uploader account deleted (uploaded_by null): member → 403, admin → deleted")
    void uploaderAccountDeleted() {
      when(repository.findByIdAndTaskId(ATTACHMENT, TASK)).thenReturn(Optional.of(stored(null)));
      when(access.getMembership(PROJECT, USER)).thenReturn(new ProjectMember(PROJECT, USER, ProjectRole.MEMBER));
      assertThat(catchHttp(() -> service.delete(TASK, ATTACHMENT, USER)).getStatus()).isEqualTo(403);

      when(access.getMembership(PROJECT, USER)).thenReturn(new ProjectMember(PROJECT, USER, ProjectRole.ADMIN));
      service.delete(TASK, ATTACHMENT, USER);
      verify(repository).deleteById(ATTACHMENT);
    }

    @Test
    @DisplayName("an uploader demoted to viewer fails the member gate, nothing deleted")
    void demotedUploader() {
      doThrow(new ForbiddenException(Json.map("statusCode", 403, "message", "This action requires at least member role")))
          .when(access).ensureTaskRole(TASK, USER, ProjectRole.MEMBER);

      assertThatThrownBy(() -> service.delete(TASK, ATTACHMENT, USER)).isInstanceOf(ForbiddenException.class);
      verify(repository, never()).findByIdAndTaskId(anyString(), anyString());
      verify(repository, never()).deleteById(any());
    }

    @Test
    @DisplayName("an attachment id from another task → 404, nothing deleted")
    void crossTaskIdOnDelete() {
      when(repository.findByIdAndTaskId(ATTACHMENT, TASK)).thenReturn(Optional.empty());

      HttpException e = catchHttp(() -> service.delete(TASK, ATTACHMENT, USER));
      assertThat(e.getStatus()).isEqualTo(404);
      verify(repository, never()).deleteById(any());
    }
  }
}
