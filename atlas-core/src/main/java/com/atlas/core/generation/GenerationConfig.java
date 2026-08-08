package com.atlas.core.generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the OpenAI chat generator by hand rather than via Spring AI's OpenAiChatAutoConfiguration
 * (excluded — see application.yml), for the same reason {@code EmbeddingConfig} does: the
 * auto-configuration validates the API key at bean-creation time and would fail startup whenever no
 * key is configured, but CI (and any keyless deployment) must still load the full context.
 *
 * <p>{@link #chatGenerator} only exists when {@code spring.ai.openai.api-key} has text, so in
 * keyless mode there is no {@link ChatGenerator} bean at all — the chat endpoint detects its
 * absence and returns 503, while search continues to work keyless.
 */
@Configuration
class GenerationConfig {

  private static final Logger log = LoggerFactory.getLogger(GenerationConfig.class);

  @Bean
  @ConditionalOnExpression(
      "T(org.springframework.util.StringUtils).hasText('${spring.ai.openai.api-key:}')")
  ChatGenerator chatGenerator(
      @Value("${spring.ai.openai.api-key}") String apiKey, GenerationProperties properties) {
    OpenAiChatOptions options = chatOptions(properties);

    OpenAiApi api = OpenAiApi.builder().apiKey(apiKey).build();
    OpenAiChatModel chatModel =
        OpenAiChatModel.builder().openAiApi(api).defaultOptions(options).build();

    // Effective settings at startup, so it's clear from the logs whether temperature was applied.
    log.info(
        "Chat generation enabled: model={} temperature={} streamUsage=true",
        properties.model(),
        options.getTemperature() != null
            ? options.getTemperature()
            : "omitted (model family rejects an explicit temperature)");

    return new SpringAiChatGenerator(chatModel, properties.model());
  }

  /**
   * The default request options for both the sync and streaming paths.
   *
   * <p>{@code streamUsage(true)} sets OpenAI's {@code stream_options.include_usage} so token usage
   * is reported on the final streamed chunk — without it, a streamed response carries <em>no</em>
   * usage and the answer's token count reads as zero (the sync {@code call()} always returns usage,
   * so only streaming was affected). Spring AI strips {@code stream_options} from non-streaming
   * requests on its own, so enabling it here is safe for {@code call()} too. Temperature is applied
   * only for model families that accept an explicit value (see {@link ChatModels}).
   */
  static OpenAiChatOptions chatOptions(GenerationProperties properties) {
    OpenAiChatOptions.Builder builder =
        OpenAiChatOptions.builder().model(properties.model()).streamUsage(true);
    if (ChatModels.supportsCustomTemperature(properties.model())) {
      builder.temperature(properties.temperature());
    }
    return builder.build();
  }
}
