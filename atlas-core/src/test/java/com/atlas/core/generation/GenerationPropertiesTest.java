package com.atlas.core.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Validation of the generation tuning knobs. */
class GenerationPropertiesTest {

  @Test
  void acceptsValidValues() {
    GenerationProperties properties = new GenerationProperties("gpt-5-mini", 0.1, 6000, 3);

    assertThat(properties.model()).isEqualTo("gpt-5-mini");
    assertThat(properties.temperature()).isEqualTo(0.1);
    assertThat(properties.contextBudget()).isEqualTo(6000);
    assertThat(properties.historyTurns()).isEqualTo(3);
  }

  @Test
  void rejectsBlankModel() {
    assertThatThrownBy(() -> new GenerationProperties("  ", 0.1, 6000, 3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("model");
  }

  @Test
  void rejectsTemperatureOutOfRange() {
    assertThatThrownBy(() -> new GenerationProperties("gpt-5-mini", -0.1, 6000, 3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("temperature");
    assertThatThrownBy(() -> new GenerationProperties("gpt-5-mini", 2.5, 6000, 3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("temperature");
  }

  @Test
  void rejectsNonPositiveContextBudget() {
    assertThatThrownBy(() -> new GenerationProperties("gpt-5-mini", 0.1, 0, 3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("context-budget");
  }

  @Test
  void rejectsNegativeHistoryTurns() {
    assertThatThrownBy(() -> new GenerationProperties("gpt-5-mini", 0.1, 6000, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("history-turns");
  }
}
