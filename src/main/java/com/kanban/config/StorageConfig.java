package com.kanban.config;

import com.kanban.common.storage.FileStorage;
import com.kanban.common.storage.S3FileStorage;
import com.kanban.common.storage.StorageProperties;
import com.kanban.modules.attachment.AttachmentProperties;
import jakarta.servlet.MultipartConfigElement;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3 API clients, upload limits and the storage cleanup schedule for task attachments (JSP-40).
 * Building the clients opens no connection.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({StorageProperties.class, AttachmentProperties.class})
public class StorageConfig {
  @Bean(destroyMethod = "close")
  public S3Client s3Client(StorageProperties properties) {
    return S3Client.builder()
        .httpClientBuilder(UrlConnectionHttpClient.builder()
            .connectionTimeout(Duration.ofSeconds(5))
            .socketTimeout(Duration.ofSeconds(30)))
        .endpointOverride(properties.endpoint())
        .region(Region.of(properties.region()))
        .credentialsProvider(credentials(properties))
        .forcePathStyle(properties.forcePathStyle())
        // SDK >= 2.30 adds CRC checksums to every request by default, which some S3-compatible
        // stores reject. Send and validate them only where an operation requires one.
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .build();
  }

  @Bean(destroyMethod = "close")
  public S3Presigner s3Presigner(StorageProperties properties) {
    return S3Presigner.builder()
        .endpointOverride(properties.endpoint())
        .region(Region.of(properties.region()))
        .credentialsProvider(credentials(properties))
        .serviceConfiguration(S3Configuration.builder()
            .pathStyleAccessEnabled(properties.forcePathStyle())
            .build())
        .build();
  }

  @Bean
  public FileStorage fileStorage(S3Client s3Client, S3Presigner s3Presigner, StorageProperties properties) {
    return new S3FileStorage(s3Client, s3Presigner, properties.bucket());
  }

  /** One setting (ATTACHMENT_MAX_SIZE) drives both limits; the request limit leaves room for multipart headers. */
  @Bean
  public MultipartConfigElement multipartConfigElement(AttachmentProperties attachments) {
    MultipartConfigFactory factory = new MultipartConfigFactory();
    factory.setMaxFileSize(attachments.maxSize());
    factory.setMaxRequestSize(DataSize.ofBytes(attachments.maxSize().toBytes() + DataSize.ofMegabytes(1).toBytes()));
    return factory.createMultipartConfig();
  }

  /** Blank keys mean storage is not configured yet: boot anyway, and let storage calls fail (503). */
  private static AwsCredentialsProvider credentials(StorageProperties properties) {
    if (!properties.hasCredentials()) {
      return AnonymousCredentialsProvider.create();
    }
    return StaticCredentialsProvider.create(
        AwsBasicCredentials.create(properties.accessKeyId(), properties.secretAccessKey()));
  }
}
