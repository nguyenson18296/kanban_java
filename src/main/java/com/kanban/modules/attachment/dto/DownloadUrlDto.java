package com.kanban.modules.attachment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record DownloadUrlDto(
    @Schema(description = "Signed storage URL; navigate to it (no Authorization header) to download the file")
    String url,
    @Schema(example = "2026-09-26T10:05:00.000Z", description = "When the URL stops working")
    Instant expires_at) {}
