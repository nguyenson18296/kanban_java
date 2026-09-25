package com.kanban.modules.dependency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kanban.modules.task.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The SQL is unverified by tests (like every native query); this covers the row mapping only. */
class JpaDependencyQueriesTest {
  @Test
  @DisplayName("statusOf maps the wire value to the enum")
  void mapsWireValues() {
    assertThat(JpaDependencyQueries.statusOf("in_progress")).isEqualTo(TaskStatus.IN_PROGRESS);
  }

  @Test
  @DisplayName("statusOf fails fast on a value the enum does not know, like the entity converter")
  void rejectsUnknownValues() {
    assertThatThrownBy(() -> JpaDependencyQueries.statusOf("archived"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("archived");
  }
}
