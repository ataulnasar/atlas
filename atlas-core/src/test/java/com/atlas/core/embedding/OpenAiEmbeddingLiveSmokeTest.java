package com.atlas.core.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Live smoke test against the real OpenAI embeddings API — the ONE test in this codebase allowed to
 * need a real key, and even this one skips entirely unless {@code SPRING_AI_OPENAI_API_KEY} is
 * actually set. Never runs in CI (no key there); run it locally once after any change to {@link
 * EmbeddingConfig} to prove the real wiring still produces working embeddings.
 *
 * <p>Uses {@link ApplicationContextRunner} against {@link EmbeddingConfig} directly rather than the
 * full {@code @SpringBootTest} app context — this test has nothing to do with the database, so it
 * has no reason to require Docker/Testcontainers to run.
 */
@EnabledIfEnvironmentVariable(named = "SPRING_AI_OPENAI_API_KEY", matches = ".+")
class OpenAiEmbeddingLiveSmokeTest {

  @Test
  void embedsTwoDifferentStringsInto1536DimensionVectorsThatDiffer() {
    String apiKey = System.getenv("SPRING_AI_OPENAI_API_KEY");

    new ApplicationContextRunner()
        .withUserConfiguration(EmbeddingConfig.class)
        .withBean(EmbeddingProperties.class, () -> new EmbeddingProperties(100))
        .withPropertyValues(
            "spring.ai.openai.api-key=" + apiKey,
            "spring.ai.openai.embedding.options.model=text-embedding-3-small")
        .run(
            context -> {
              assertThat(context).hasSingleBean(EmbeddingService.class);
              EmbeddingService embeddingService = context.getBean(EmbeddingService.class);

              List<float[]> vectors =
                  embeddingService.embed(
                      List.of(
                          "The quick brown fox jumps over the lazy dog.",
                          "GDPR governs the processing of personal data in the European Union."));

              assertThat(vectors).hasSize(2);
              assertThat(vectors.get(0)).hasSize(1536);
              assertThat(vectors.get(1)).hasSize(1536);
              assertThat(vectors.get(0)).isNotEqualTo(vectors.get(1));
            });
  }
}
