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

  @Test
  void defaultCostAppliesGpt5MiniPublishedRates() {
    GenerationProperties.Cost cost = new GenerationProperties("gpt-5-mini", 0.1, 6000, 3).cost();
    assertThat(cost.inputPricePerMillionTokens()).isEqualTo(0.25);
    assertThat(cost.outputPricePerMillionTokens()).isEqualTo(2.00);
  }

  @Test
  void estimateUsdIsInputAndOutputPricedSeparatelyPerMillionTokens() {
    GenerationProperties.Cost cost = new GenerationProperties.Cost(0.25, 2.00);

    // One million of each: exactly the two per-million prices summed.
    assertThat(cost.estimateUsd(1_000_000, 1_000_000)).isEqualTo(2.25);
    // A realistic request: 3779 in, 146 out -> 3779*0.25/1e6 + 146*2.00/1e6.
    assertThat(cost.estimateUsd(3779, 146))
        .isEqualTo(0.00123675, org.assertj.core.data.Offset.offset(1e-9));
  }

  @Test
  void estimateUsdTreatsNullTokenCountsAsZero() {
    GenerationProperties.Cost cost = new GenerationProperties.Cost(0.25, 2.00);
    assertThat(cost.estimateUsd(null, null)).isEqualTo(0.0);
    assertThat(cost.estimateUsd(1_000_000, null)).isEqualTo(0.25);
  }

  @Test
  void rejectsNegativeCostPrices() {
    assertThatThrownBy(() -> new GenerationProperties.Cost(-0.1, 2.00))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cost");
  }
}
