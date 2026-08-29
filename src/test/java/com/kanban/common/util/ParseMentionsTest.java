package com.kanban.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.kanban.common.util.ParseMentions.Mentions;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of parse-mentions.util.spec.ts */
class ParseMentionsTest {
  @Test
  @DisplayName("extracts UUIDs from data-mention-id")
  void extractsIds() {
    Mentions m = ParseMentions.parseMentions("<span data-mention-id=\"a1b2\">@Alice</span> hello");
    assertThat(m.ids()).containsExactly("a1b2");
    assertThat(m.names()).isEmpty();
  }

  @Test
  @DisplayName("extracts full names from data-mention spans without an id")
  void extractsNames() {
    Mentions m = ParseMentions.parseMentions("<span data-mention=\"Bob Smith\">@Bob Smith</span>");
    assertThat(m.ids()).isEmpty();
    assertThat(m.names()).containsExactly("Bob Smith");
  }

  @Test
  @DisplayName("prefers the id and does not double-count a span carrying both")
  void prefersId() {
    Mentions m = ParseMentions.parseMentions(
        "<span data-mention=\"Alice\" data-mention-id=\"a1b2\">@Alice</span>");
    assertThat(m.ids()).containsExactly("a1b2");
    assertThat(m.names()).isEmpty();
  }

  @Test
  @DisplayName("falls back to plain @Full Name text mentions")
  void plainTextFallback() {
    Mentions m = ParseMentions.parseMentions("hey @John Doe please review");
    assertThat(m.ids()).isEmpty();
    assertThat(m.names()).containsExactly("John Doe");
  }

  @Test
  @DisplayName("returns empty arrays when there are no mentions")
  void noMentions() {
    assertThat(ParseMentions.parseMentions("<p>no mentions here</p>")).isEqualTo(new Mentions(List.of(), List.of()));
  }
}
