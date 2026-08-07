package com.atlas.core.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EmbeddingPropertiesTest {

  @Test
  void rejectsNonPositiveBatchSize() {
    assertThatThrownBy(() -> new EmbeddingProperties(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsBatchSizeAboveOpenAisPerCallLimit() {
    assertThatThrownBy(() -> new EmbeddingProperties(2049))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsAValidConfiguration() {
    assertThat(new EmbeddingProperties(100).batchSize()).isEqualTo(100);
  }

  @Test
  void acceptsTheOpenAiPerCallLimitExactly() {
    assertThat(new EmbeddingProperties(2048).batchSize()).isEqualTo(2048);
  }
}
