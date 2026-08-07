package com.atlas.core.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

class SpringAiEmbeddingServiceTest {

  @Test
  void embedsAllTextsInASingleCallWhenWithinBatchSize() {
    EmbeddingModel model = mock(EmbeddingModel.class);
    List<String> texts = List.of("alpha", "beta");
    float[] alphaVector = {1f, 2f};
    float[] betaVector = {3f, 4f};
    when(model.embed(texts)).thenReturn(List.of(alphaVector, betaVector));

    List<float[]> result = new SpringAiEmbeddingService(model, 100).embed(texts);

    assertThat(result).containsExactly(alphaVector, betaVector);
    verify(model).embed(texts);
  }

  @Test
  void splitsTextsAcrossMultipleCallsWhenExceedingBatchSize() {
    EmbeddingModel model = mock(EmbeddingModel.class);
    List<String> batch1 = List.of("a", "b");
    List<String> batch2 = List.of("c", "d");
    List<String> batch3 = List.of("e");
    float[] va = {1f};
    float[] vb = {2f};
    float[] vc = {3f};
    float[] vd = {4f};
    float[] ve = {5f};
    when(model.embed(batch1)).thenReturn(List.of(va, vb));
    when(model.embed(batch2)).thenReturn(List.of(vc, vd));
    when(model.embed(batch3)).thenReturn(List.of(ve));

    List<float[]> result =
        new SpringAiEmbeddingService(model, 2).embed(List.of("a", "b", "c", "d", "e"));

    assertThat(result).containsExactly(va, vb, vc, vd, ve);
    verify(model).embed(batch1);
    verify(model).embed(batch2);
    verify(model).embed(batch3);
  }

  @Test
  void emptyInputReturnsEmptyResultWithoutCallingTheModel() {
    EmbeddingModel model = mock(EmbeddingModel.class);

    List<float[]> result = new SpringAiEmbeddingService(model, 100).embed(List.of());

    assertThat(result).isEmpty();
    verifyNoInteractions(model);
  }

  @Test
  void aModelFailureIsWrappedInEmbeddingException() {
    EmbeddingModel model = mock(EmbeddingModel.class);
    RuntimeException cause = new RuntimeException("401 Unauthorized");
    when(model.embed(eq(List.of("alpha")))).thenThrow(cause);

    assertThatThrownBy(() -> new SpringAiEmbeddingService(model, 100).embed(List.of("alpha")))
        .isInstanceOf(EmbeddingException.class)
        .hasCause(cause)
        .hasMessageContaining("1 text(s)");
  }

  @Test
  void aFailureInALaterBatchStopsProcessingRatherThanReturningPartialResults() {
    EmbeddingModel model = mock(EmbeddingModel.class);
    float[] va = {1f};
    when(model.embed(List.of("a"))).thenReturn(List.of(va));
    RuntimeException cause = new RuntimeException("rate limited");
    when(model.embed(List.of("b"))).thenThrow(cause);

    assertThatThrownBy(() -> new SpringAiEmbeddingService(model, 1).embed(List.of("a", "b", "c")))
        .isInstanceOf(EmbeddingException.class)
        .hasCause(cause);
    verify(model, never()).embed(List.of("c"));
  }
}
