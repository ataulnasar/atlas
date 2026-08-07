package com.atlas.core.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.core.document.ChunkEmbeddingService.BackfillResult;
import com.atlas.core.document.ChunkRepository.ChunkToEmbed;
import com.atlas.core.embedding.EmbeddingException;
import com.atlas.core.embedding.EmbeddingProperties;
import com.atlas.core.embedding.FakeEmbeddingService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChunkEmbeddingServiceTest {

  private static final EmbeddingProperties FAST_PROPS =
      new EmbeddingProperties(100, Duration.ofMillis(1), 3);

  @Test
  void backfillResumesAfterATransientRateLimit() {
    ChunkRepository repository = mock(ChunkRepository.class);
    UUID documentId = UUID.randomUUID();
    List<ChunkToEmbed> batch =
        List.of(
            new ChunkToEmbed(UUID.randomUUID(), documentId, "first chunk"),
            new ChunkToEmbed(UUID.randomUUID(), documentId, "second chunk"));
    when(repository.findNextChunksWithoutEmbeddingForReadyDocuments(anyInt()))
        .thenReturn(batch)
        .thenReturn(List.of());

    FakeEmbeddingService embeddingService = new FakeEmbeddingService();
    embeddingService.failWithRateLimitTimes(1); // one 429, then it succeeds

    BackfillResult result =
        new ChunkEmbeddingService(repository, FAST_PROPS).backfill(embeddingService);

    assertThat(result.chunksEmbedded()).isEqualTo(2);
    assertThat(result.documentsTouched()).isEqualTo(1);
    verify(repository, times(1)).updateEmbeddings(any(), any());
  }

  @Test
  void aNonRateLimitFailureIsNotRetriedAndPropagates() {
    ChunkRepository repository = mock(ChunkRepository.class);
    when(repository.findNextChunksWithoutEmbeddingForReadyDocuments(anyInt()))
        .thenReturn(List.of(new ChunkToEmbed(UUID.randomUUID(), UUID.randomUUID(), "chunk")));

    FakeEmbeddingService embeddingService = new FakeEmbeddingService();
    embeddingService.setFailing(true); // a hard failure, not a rate limit

    assertThatThrownBy(
            () -> new ChunkEmbeddingService(repository, FAST_PROPS).backfill(embeddingService))
        .isInstanceOf(EmbeddingException.class);
    verify(repository, never()).updateEmbeddings(any(), any());
  }

  @Test
  void aPersistentRateLimitEventuallyGivesUpAfterMaxAttempts() {
    ChunkRepository repository = mock(ChunkRepository.class);
    when(repository.findNextChunksWithoutEmbeddingForReadyDocuments(anyInt()))
        .thenReturn(List.of(new ChunkToEmbed(UUID.randomUUID(), UUID.randomUUID(), "chunk")));

    FakeEmbeddingService embeddingService = new FakeEmbeddingService();
    embeddingService.failWithRateLimitTimes(10); // never recovers within the attempt budget

    assertThatThrownBy(
            () -> new ChunkEmbeddingService(repository, FAST_PROPS).backfill(embeddingService))
        .isInstanceOf(EmbeddingException.class);
    verify(repository, never()).updateEmbeddings(any(), any());
  }

  @Test
  void isRateLimitedDetectsA429AnywhereInTheCauseChain() {
    Throwable wrapped =
        new EmbeddingException(
            "Failed to embed batch",
            new RuntimeException("429 Too Many Requests: rate limit reached"));
    assertThat(ChunkEmbeddingService.isRateLimited(wrapped)).isTrue();

    Throwable notRateLimited =
        new EmbeddingException("boom", new RuntimeException("connection reset"));
    assertThat(ChunkEmbeddingService.isRateLimited(notRateLimited)).isFalse();
  }

  @Test
  void extractSuggestedWaitParsesTheProviderHintWhenPresent() {
    assertThat(
            ChunkEmbeddingService.extractSuggestedWait(
                new RuntimeException("Rate limit reached. Please try again in 1.257s.")))
        .contains(Duration.ofMillis(1257));

    assertThat(
            ChunkEmbeddingService.extractSuggestedWait(
                new RuntimeException("Please try again in 800ms.")))
        .contains(Duration.ofMillis(800));

    assertThat(
            ChunkEmbeddingService.extractSuggestedWait(new RuntimeException("429, no hint here")))
        .isEmpty();
  }
}
