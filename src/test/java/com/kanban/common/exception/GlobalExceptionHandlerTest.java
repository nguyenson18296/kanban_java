package com.kanban.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.multipart.MultipartException;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {
  @Test
  @DisplayName("a rejected multipart request is logged with its cause (server faults like a missing temp dir land here)")
  void multipartRejectionIsLogged(CapturedOutput output) {
    MultipartException ex = new MultipartException("Failed to parse multipart servlet request",
        new IllegalStateException("The temporary upload location [/tmp/tomcat.1996] is not valid"));

    var response = new GlobalExceptionHandler("10MB").handleMultipart(ex);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(output).contains("Rejected multipart request").contains("temporary upload location");
  }
}
