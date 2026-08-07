package com.atlas.core.embedding;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the OpenAI embedding beans by hand instead of relying on Spring AI's own
 * OpenAiEmbeddingAutoConfiguration (excluded — see application.yml), because that
 * auto-configuration eagerly validates the API key at bean-creation time and would fail application
 * startup entirely whenever no key is configured. That's unacceptable here: CI has no key and must
 * still be able to load the full application context.
 *
 * <p>{@link #embeddingModel} only runs when {@code spring.ai.openai.api-key} actually has text, so
 * without a key neither it nor {@link #embeddingService} exist as beans at all — nothing in Atlas
 * yet requires an {@link EmbeddingService} to be present, so an absent bean is a clean no-op rather
 * than a startup failure.
 */
@Configuration
class EmbeddingConfig {

  @Bean
  @ConditionalOnExpression(
      "T(org.springframework.util.StringUtils).hasText('${spring.ai.openai.api-key:}')")
  EmbeddingModel embeddingModel(
      @Value("${spring.ai.openai.api-key}") String apiKey,
      @Value("${spring.ai.openai.embedding.options.model}") String model) {
    OpenAiApi api = OpenAiApi.builder().apiKey(apiKey).build();
    OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder().model(model).build();
    return new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options);
  }

  @Bean
  @ConditionalOnBean(EmbeddingModel.class)
  EmbeddingService embeddingService(EmbeddingModel embeddingModel, EmbeddingProperties properties) {
    return new SpringAiEmbeddingService(embeddingModel, properties.batchSize());
  }
}
