package com.kanban.modules.attachment;

import com.kanban.common.api.PaginatedResponse;
import com.kanban.common.pipes.Param;
import com.kanban.common.validation.ValidatedQuery;
import com.kanban.modules.attachment.dto.AttachmentQueryDto;
import com.kanban.modules.attachment.dto.DownloadUrlDto;
import com.kanban.modules.auth.decorators.CurrentUser;
import com.kanban.modules.auth.guards.JwtAuth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Task Attachments")
@RestController
@RequestMapping("/tasks")
public class AttachmentController {
  private final AttachmentService attachmentService;

  public AttachmentController(AttachmentService attachmentService) {
    this.attachmentService = attachmentService;
  }

  @PostMapping("/{taskId}/attachments")
  @ResponseStatus(HttpStatus.CREATED)
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Attach one file (PNG, JPEG, GIF, WebP or PDF) to a task")
  @Parameter(name = "taskId", description = "Task UUID")
  @ApiResponse(responseCode = "201", description = "Attachment created")
  @ApiResponse(responseCode = "400", description = "No file part, or an empty file")
  @ApiResponse(responseCode = "403", description = "Requires at least member role")
  @ApiResponse(responseCode = "404", description = "Task not found")
  @ApiResponse(responseCode = "413", description = "File is larger than the configured limit")
  @ApiResponse(responseCode = "415", description = "File type is not allowed")
  @ApiResponse(responseCode = "503", description = "File storage is unavailable")
  public Map<String, Object> upload(@Param(value = "taskId", pipe = Param.Pipe.UUID) String taskId,
      // The one request input not bound through @ValidatedBody: it cannot read multipart, so
      // every check on the file lives in AttachmentService. Don't copy this for JSON routes.
      // The Swagger annotation documents the multipart body without `consumes` on the mapping,
      // which would turn a JSON request into HttpMediaTypeNotSupportedException (a generic 500).
      @RequestBody(content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
          schemaProperties = @SchemaProperty(name = "file", schema = @Schema(type = "string", format = "binary"))))
      @RequestPart(value = "file", required = false) MultipartFile file,
      @CurrentUser("id") String userId) {
    return attachmentService.upload(taskId, file, userId).toJson();
  }

  @GetMapping("/{taskId}/attachments")
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "List a task's attachments, newest first (paginated)")
  @Parameter(name = "taskId", description = "Task UUID")
  @ApiResponse(responseCode = "200", description = "Paginated list of attachments")
  @ApiResponse(responseCode = "404", description = "Task not found")
  public PaginatedResponse<Map<String, Object>> list(@Param(value = "taskId", pipe = Param.Pipe.UUID) String taskId,
      @ValidatedQuery AttachmentQueryDto query, @CurrentUser("id") String userId) {
    return attachmentService.list(taskId, query, userId);
  }

  @GetMapping("/{taskId}/attachments/{attachmentId}/download")
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Get a short-lived signed URL that downloads the file")
  @Parameter(name = "taskId", description = "Task UUID")
  @Parameter(name = "attachmentId", description = "Attachment UUID")
  @ApiResponse(responseCode = "200", description = "Signed URL and its expiry")
  @ApiResponse(responseCode = "404", description = "Task or attachment not found")
  @ApiResponse(responseCode = "503", description = "File storage is unavailable")
  public DownloadUrlDto download(@Param(value = "taskId", pipe = Param.Pipe.UUID) String taskId,
      @Param(value = "attachmentId", pipe = Param.Pipe.UUID) String attachmentId,
      @CurrentUser("id") String userId) {
    return attachmentService.downloadUrl(taskId, attachmentId, userId);
  }

  @DeleteMapping("/{taskId}/attachments/{attachmentId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Remove an attachment (uploader, or a project admin/owner)")
  @Parameter(name = "taskId", description = "Task UUID")
  @Parameter(name = "attachmentId", description = "Attachment UUID")
  @ApiResponse(responseCode = "204", description = "Removed; the stored file is deleted shortly after")
  @ApiResponse(responseCode = "403", description = "Not the uploader and not a project admin")
  @ApiResponse(responseCode = "404", description = "Task or attachment not found")
  public void delete(@Param(value = "taskId", pipe = Param.Pipe.UUID) String taskId,
      @Param(value = "attachmentId", pipe = Param.Pipe.UUID) String attachmentId,
      @CurrentUser("id") String userId) {
    attachmentService.delete(taskId, attachmentId, userId);
  }
}
