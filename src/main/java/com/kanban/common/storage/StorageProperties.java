package com.kanban.common.storage;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.storage")
public record StorageProperties(
    @DefaultValue("http://localhost:9000") URI endpoint,
    @DefaultValue("us-east-1") String region,
    @DefaultValue("task-attachments") String bucket,
    @DefaultValue("") String accessKeyId,
    @DefaultValue("") String secretAccessKey,
    @DefaultValue("true") boolean forcePathStyle) {
  public StorageProperties {
    if (endpoint == null || region == null || region.isBlank() || bucket == null || bucket.isBlank()) {
      throw new IllegalArgumentException("Storage requires an endpoint, a region and a bucket");
    }
  }

  public boolean hasCredentials() {
    return accessKeyId != null && !accessKeyId.isBlank()
        && secretAccessKey != null && !secretAccessKey.isBlank();
  }
}
