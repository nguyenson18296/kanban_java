package com.kanban.modules.attachment;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("app.attachments")
public record AttachmentProperties(
    @DefaultValue("10MB") DataSize maxSize,
    @DefaultValue("5m") Duration downloadUrlTtl) {
  public AttachmentProperties {
    // SigV4 presigned URLs are valid for at most 7 days.
    if (maxSize == null || maxSize.toBytes() < 1 || downloadUrlTtl == null
        || downloadUrlTtl.toSeconds() < 1 || downloadUrlTtl.compareTo(Duration.ofDays(7)) > 0) {
      throw new IllegalArgumentException(
          "Attachments require a positive max size and a download URL TTL between 1s and 7d");
    }
  }
}
