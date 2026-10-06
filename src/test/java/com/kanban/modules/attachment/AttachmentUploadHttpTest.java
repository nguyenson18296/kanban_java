package com.kanban.modules.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanban.common.exception.GlobalExceptionHandler;
import com.kanban.config.JacksonConfig;
import com.kanban.config.StorageConfig;
import com.kanban.config.WebMvcConfig;
import com.kanban.modules.auth.AuthService;
import com.kanban.modules.auth.JwtService;
import com.kanban.modules.auth.guards.JwtAuthInterceptor;
import com.kanban.modules.auth.interfaces.JwtPayload;
import com.kanban.modules.project.ProjectAccessService;
import com.kanban.modules.project.guards.ProjectRoleInterceptor;
import com.kanban.modules.user.User;
import com.kanban.modules.user.UserRole;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.multipart.MultipartFile;

/**
 * Real Tomcat multipart parsing, which MockMvc skips: size limits, lazy parsing, and whether
 * an over-limit client still reads the 413. The service and auth are mocked — no PostgreSQL,
 * no storage — so this runs in the default `mvn test`.
 */
class AttachmentUploadHttpTest {
  private static final String TASK_ID = "33333333-3333-4333-8333-333333333333";

  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private ServletWebServerApplicationContext app;

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class,
      HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class})
  @Import({AttachmentController.class, StorageConfig.class, WebMvcConfig.class, JacksonConfig.class,
      GlobalExceptionHandler.class, JwtAuthInterceptor.class, ProjectRoleInterceptor.class})
  static class TestApplication {
    @Bean
    AttachmentService attachmentService() {
      return mock(AttachmentService.class);
    }

    @Bean
    JwtService jwtService() {
      JwtService jwt = mock(JwtService.class);
      when(jwt.verify("tok")).thenReturn(new JwtPayload("u1", "a@b.co", "backend_developer"));
      return jwt;
    }

    @Bean
    AuthService authService() {
      AuthService auth = mock(AuthService.class);
      when(auth.validateUserById("u1")).thenReturn(new User("u1", "a@b.co", "A", UserRole.BACKEND_DEVELOPER, null, true));
      return auth;
    }

    @Bean
    ProjectAccessService projectAccessService() {
      return mock(ProjectAccessService.class);
    }
  }

  @BeforeEach
  void start() {
    // Production YAML (resolve-lazily, max-swallow-size) with a 1 MB limit to keep uploads small;
    // never the developer's .env.
    app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(TestApplication.class).run(
        "--spring.config.location=classpath:/application.yml", "--spring.config.import=",
        "--server.address=127.0.0.1", "--server.port=0", "--spring.main.banner-mode=off",
        "--logging.level.root=ERROR", "--app.attachments.max-size=1MB");
  }

  @AfterEach
  void stop() {
    app.close();
    client.close();
  }

  private String url(String path) {
    return "http://127.0.0.1:" + app.getWebServer().getPort() + path;
  }

  private HttpResponse<String> upload(int fileBytes, String... headers) throws Exception {
    return upload("shot.png", fileBytes, headers);
  }

  /** Browsers send the file name as raw UTF-8 in the part header. */
  private HttpResponse<String> upload(String fileName, int fileBytes, String... headers) throws Exception {
    String boundary = "jsp40boundary";
    byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
        + "\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8);
    byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
    byte[] body = new byte[head.length + fileBytes + tail.length];
    System.arraycopy(head, 0, body, 0, head.length);
    System.arraycopy(tail, 0, body, head.length + fileBytes, tail.length);
    var request = HttpRequest.newBuilder(URI.create(url("/api/tasks/" + TASK_ID + "/attachments")))
        .timeout(Duration.ofSeconds(10))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    if (headers.length > 0) {
      request.headers(headers);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  @DisplayName("an upload far over the limit still gets the readable 413 body, not a reset connection")
  void oversizedUploadGets413() throws Exception {
    var response = upload(8 * 1024 * 1024, "Authorization", "Bearer tok");

    assertThat(response.statusCode()).isEqualTo(413);
    assertThat(response.body()).isEqualTo(
        "{\"message\":\"File is too large. The maximum size is 1 MB.\",\"error\":\"Payload Too Large\",\"statusCode\":413}");
    verifyNoInteractions(app.getBean(AttachmentService.class));
  }

  @Test
  @DisplayName("an anonymous oversized upload gets 401: the body is never parsed before the JWT check")
  void anonymousUploadGets401() throws Exception {
    var response = upload(8 * 1024 * 1024);

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.body()).isEqualTo("{\"message\":\"Unauthorized\",\"statusCode\":401}");
  }

  @Test
  @DisplayName("a 255-character Vietnamese file name reaches the service intact (not a false 413)")
  void longNonAsciiNameReachesService() throws Exception {
    String longName = "ả".repeat(251) + ".png";
    TaskAttachment saved = new TaskAttachment(TASK_ID, "u1", "x.png", "image/png", 16, "k");
    saved.setId("55555555-5555-4555-8555-555555555555");
    AttachmentService service = app.getBean(AttachmentService.class);
    when(service.upload(eq(TASK_ID), any(), eq("u1"))).thenReturn(saved);

    var response = upload(longName, 16, "Authorization", "Bearer tok");

    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    ArgumentCaptor<MultipartFile> file = ArgumentCaptor.forClass(MultipartFile.class);
    verify(service).upload(eq(TASK_ID), file.capture(), eq("u1"));
    assertThat(file.getValue().getOriginalFilename()).isEqualTo(longName);
  }

  @Test
  @DisplayName("a small upload reaches the service, and Swagger documents a multipart body")
  void smallUploadReachesService() throws Exception {
    TaskAttachment saved = new TaskAttachment(TASK_ID, "u1", "shot.png", "image/png", 16, "k");
    saved.setId("55555555-5555-4555-8555-555555555555");
    when(app.getBean(AttachmentService.class).upload(eq(TASK_ID), any(), eq("u1"))).thenReturn(saved);

    assertThat(upload(16, "Authorization", "Bearer tok").statusCode()).isEqualTo(201);

    var docs = client.send(HttpRequest.newBuilder(URI.create(url("/api/docs-json"))).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    var content = new ObjectMapper().readTree(docs.body()).path("paths").path("/api/tasks/{taskId}/attachments")
        .path("post").path("requestBody").path("content");
    assertThat(content.has("multipart/form-data")).isTrue();
  }
}
