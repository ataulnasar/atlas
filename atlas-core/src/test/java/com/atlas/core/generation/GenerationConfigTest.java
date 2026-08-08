package com.atlas.core.generation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Pins the request options that {@link GenerationConfig} builds. The token-usage streaming bug was
 * here: without {@code streamUsage(true)}, OpenAI omits usage from the streamed response and the
 * answer's token count reads as zero. A fake generator can't prove the provider's behaviour, so
 * this pins the option that drives it (the real path is verified against a live key — see the PR
 * notes / atlas-ui verification).
 */
class GenerationConfigTest {

  @Test
  void enablesStreamUsageSoStreamedAnswersReportTokens() {
    OpenAiChatOptions options =
        GenerationConfig.chatOptions(new GenerationProperties("gpt-5-mini", 0.1, 6000, 3));

    assertThat(options.getStreamUsage()).isTrue();
    assertThat(options.getModel()).isEqualTo("gpt-5-mini");
  }

  @Test
  void omitsTemperatureForReasoningModelsButStillEnablesStreamUsage() {
    // gpt-5 family rejects an explicit temperature (see ChatModels); usage must still be enabled.
    OpenAiChatOptions options =
        GenerationConfig.chatOptions(new GenerationProperties("gpt-5-mini", 0.1, 6000, 3));

    assertThat(options.getTemperature()).isNull();
    assertThat(options.getStreamUsage()).isTrue();
  }

  @Test
  void appliesTemperatureForClassicModelsWithStreamUsageOn() {
    OpenAiChatOptions options =
        GenerationConfig.chatOptions(new GenerationProperties("gpt-4o-mini", 0.2, 6000, 3));

    assertThat(options.getTemperature()).isEqualTo(0.2);
    assertThat(options.getStreamUsage()).isTrue();
  }
}
