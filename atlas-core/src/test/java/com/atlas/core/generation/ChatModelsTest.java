package com.atlas.core.generation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Which model families accept an explicit temperature. */
class ChatModelsTest {

  @Test
  void gpt5FamilyRejectsExplicitTemperature() {
    // The default model and its siblings — reasoning models that only accept the default (1).
    assertThat(ChatModels.supportsCustomTemperature("gpt-5-mini")).isFalse();
    assertThat(ChatModels.supportsCustomTemperature("gpt-5")).isFalse();
    assertThat(ChatModels.supportsCustomTemperature("gpt-5-nano")).isFalse();
  }

  @Test
  void oSeriesReasoningModelsRejectExplicitTemperature() {
    assertThat(ChatModels.supportsCustomTemperature("o1")).isFalse();
    assertThat(ChatModels.supportsCustomTemperature("o3-mini")).isFalse();
    assertThat(ChatModels.supportsCustomTemperature("o4-mini")).isFalse();
  }

  @Test
  void classicChatFamiliesAcceptExplicitTemperature() {
    assertThat(ChatModels.supportsCustomTemperature("gpt-4o-mini")).isTrue();
    assertThat(ChatModels.supportsCustomTemperature("gpt-4.1-mini")).isTrue();
    assertThat(ChatModels.supportsCustomTemperature("gpt-3.5-turbo")).isTrue();
  }

  @Test
  void checkIsCaseInsensitive() {
    assertThat(ChatModels.supportsCustomTemperature("GPT-5-MINI")).isFalse();
    assertThat(ChatModels.supportsCustomTemperature("GPT-4O-MINI")).isTrue();
  }
}
