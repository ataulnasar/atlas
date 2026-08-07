package com.atlas.core.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class EmbeddingPropertiesTest {

  private static final Duration BACKOFF = Duration.ofSeconds(5);

  @Test
  void rejectsNonPositiveBatchSize() {
    assertThatThrownBy(() -> new EmbeddingProperties(0, BACKOFF, 3))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsBatchSizeAboveOpenAisPerCallLimit() {
    assertThatThrownBy(() -> new EmbeddingProperties(2049, BACKOFF, 3))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNegativeRateLimitBackoff() {
    assertThatThrownBy(() -> new EmbeddingProperties(100, Duration.ofSeconds(-1), 3))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMaxAttemptsBelowOne() {
    assertThatThrownBy(() -> new EmbeddingProperties(100, BACKOFF, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsAValidConfiguration() {
    EmbeddingProperties properties = new EmbeddingProperties(100, BACKOFF, 3);
    assertThat(properties.batchSize()).isEqualTo(100);
    assertThat(properties.rateLimitBackoff()).isEqualTo(BACKOFF);
    assertThat(properties.maxAttemptsPerBatch()).isEqualTo(3);
  }

  @Test
  void acceptsTheOpenAiPerCallLimitExactly() {
    assertThat(new EmbeddingProperties(2048, BACKOFF, 3).batchSize()).isEqualTo(2048);
  }
}
