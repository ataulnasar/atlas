package com.atlas.core.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ChunkingPropertiesTest {

  @Test
  void rejectsNonPositiveMaxTokens() {
    assertThatThrownBy(() -> new ChunkingProperties(0, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNegativeOverlapTokens() {
    assertThatThrownBy(() -> new ChunkingProperties(100, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsOverlapTokensGreaterThanOrEqualToMaxTokens() {
    assertThatThrownBy(() -> new ChunkingProperties(100, 100))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsAValidConfiguration() {
    ChunkingProperties properties = new ChunkingProperties(500, 60);

    assertThat(properties.maxTokens()).isEqualTo(500);
    assertThat(properties.overlapTokens()).isEqualTo(60);
  }
}
