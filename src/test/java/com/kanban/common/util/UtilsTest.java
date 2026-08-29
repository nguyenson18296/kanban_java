package com.kanban.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class UtilsTest {
  @Test
  void durationParserMatchesJsonwebtoken() {
    assertThat(DurationParser.toSeconds("1h")).isEqualTo(3600);
    assertThat(DurationParser.toSeconds("30d")).isEqualTo(30 * 86400);
    assertThat(DurationParser.toSeconds("15m")).isEqualTo(900);
    assertThat(DurationParser.toSeconds("3600")).isEqualTo(3600);
    assertThat(DurationParser.toSeconds("2 days")).isEqualTo(2 * 86400);
  }

  @Test
  void datesFormatLikeJavaScript() {
    assertThat(Dates.iso(Instant.parse("2025-01-01T00:00:00Z"))).isEqualTo("2025-01-01T00:00:00.000Z");
    assertThat(Dates.iso(Instant.parse("2025-01-01T00:00:00.123456Z"))).isEqualTo("2025-01-01T00:00:00.123Z");
  }

  @Test
  void sanitizerMirrorsSanitizeHtmlConfig() {
    assertThat(SanitizeHtml.sanitize("<span class=\"m\" data-mention-id=\"u1\" style=\"x\">@A</span>"))
        .isEqualTo("<span class=\"m\" data-mention-id=\"u1\">@A</span>");
    assertThat(SanitizeHtml.sanitize("<a href=\"https://x.y\" target=\"_self\">x</a>"))
        .isEqualTo("<a href=\"https://x.y\" target=\"_blank\" rel=\"noopener noreferrer\">x</a>");
    assertThat(SanitizeHtml.sanitize("<img src=x onerror=y><b>bold</b><br>"))
        .isEqualTo("<b>bold</b><br />");
    assertThat(SanitizeHtml.sanitize("<div>keep text</div>")).isEqualTo("keep text");
  }

  @Test
  void emailValidatorCoversCommonShapes() {
    assertThat(com.kanban.common.validation.EmailValidator.isEmail("john@example.com")).isTrue();
    assertThat(com.kanban.common.validation.EmailValidator.isEmail("john.doe+tag@sub.example.co.uk")).isTrue();
    assertThat(com.kanban.common.validation.EmailValidator.isEmail("not-an-email")).isFalse();
    assertThat(com.kanban.common.validation.EmailValidator.isEmail("john@localhost")).isFalse();
    assertThat(com.kanban.common.validation.EmailValidator.isEmail("john@@example.com")).isFalse();
    assertThat(com.kanban.common.validation.EmailValidator.isEmail("@example.com")).isFalse();
  }
}
