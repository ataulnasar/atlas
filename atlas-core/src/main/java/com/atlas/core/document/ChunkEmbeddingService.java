package com.atlas.core.document;

import com.atlas.core.document.ChunkRepository.ChunkToEmbed;
import com.atlas.core.embedding.EmbeddingException;
import com.atlas.core.embedding.EmbeddingProperties;
import com.atlas.core.embedding.EmbeddingService;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Embeds chunk content and persists the vectors, batch by batch. Shared by two callers: the
 * ingestion path (one freshly-chunked document) and the admin backfill (every not-yet-embedded
 * chunk of every READY document). Each batch is embedded then written in its own repository
 * transaction, so a failure or interruption leaves already-embedded batches committed rather than
 * rolling back progress — which is also what makes the backfill idempotent and re-runnable.
 *
 * <p>A provider rate limit (HTTP 429) is transient — OpenAI classifies it as non-retryable and
 * Spring AI gives up immediately, so a long backfill would otherwise die partway with a 500 the
 * moment it exceeds the tokens-per-minute limit. Each batch is therefore retried with a short
 * backoff (the provider-suggested interval when it gives one) before the failure is treated as
 * real.
 */
@Component
class ChunkEmbeddingService {

  private static final Logger log = LoggerFactory.getLogger(ChunkEmbeddingService.class);

  // e.g. OpenAI: "... Please try again in 1.257s." (also matches a "...in 800ms" form).
  private static final Pattern SUGGESTED_WAIT =
      Pattern.compile("try again in\\s+([0-9]+(?:\\.[0-9]+)?)\\s*(ms|s)", Pattern.CASE_INSENSITIVE);
  // A provider-suggested wait beyond this is more likely a bug or an absurd value than a real hint.
  private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);

  private final ChunkRepository chunkRepository;
  private final int batchSize;
  private final Duration rateLimitBackoff;
  private final int maxAttemptsPerBatch;

  ChunkEmbeddingService(ChunkRepository chunkRepository, EmbeddingProperties embeddingProperties) {
    this.chunkRepository = chunkRepository;
    this.batchSize = embeddingProperties.batchSize();
    this.rateLimitBackoff = embeddingProperties.rateLimitBackoff();
    this.maxAttemptsPerBatch = embeddingProperties.maxAttemptsPerBatch();
  }

  /**
   * Embeds a single document's not-yet-embedded chunks. Propagates the {@link EmbeddingService}'s
   * exception on provider failure so the ingestion path can route the document to FAILED; any
   * batches already written stay put (the retry path deletes and re-inserts the document's chunks,
   * so a partial write can't leave stale vectors behind).
   */
  int embedDocumentChunks(UUID documentId, EmbeddingService embeddingService) {
    List<ChunkToEmbed> chunks = chunkRepository.findChunksWithoutEmbedding(documentId);
    int embedded = 0;
    for (int start = 0; start < chunks.size(); start += batchSize) {
      int end = Math.min(start + batchSize, chunks.size());
      embedAndPersistBatch(chunks.subList(start, end), embeddingService);
      embedded += end - start;
    }
    return embedded;
  }

  /**
   * Embeds every not-yet-embedded chunk of every READY document, committing each batch as it goes.
   * Idempotent by construction: a chunk is re-fetched only while its embedding is still NULL, so a
   * re-run after an interruption resumes from exactly where it stopped.
   */
  BackfillResult backfill(EmbeddingService embeddingService) {
    int totalChunks = 0;
    Set<UUID> documentsTouched = new HashSet<>();
    while (true) {
      List<ChunkToEmbed> batch =
          chunkRepository.findNextChunksWithoutEmbeddingForReadyDocuments(batchSize);
      if (batch.isEmpty()) {
        break;
      }
      embedAndPersistBatch(batch, embeddingService);
      totalChunks += batch.size();
      batch.forEach(chunk -> documentsTouched.add(chunk.documentId()));
      log.info(
          "Embedding backfill progress: {} chunk(s) embedded across {} document(s) so far",
          totalChunks,
          documentsTouched.size());
    }
    return new BackfillResult(totalChunks, documentsTouched.size());
  }

  private void embedAndPersistBatch(List<ChunkToEmbed> batch, EmbeddingService embeddingService) {
    int attempt = 1;
    while (true) {
      try {
        List<String> contents = batch.stream().map(ChunkToEmbed::content).toList();
        List<float[]> vectors = embeddingService.embed(contents);
        List<UUID> ids = batch.stream().map(ChunkToEmbed::id).toList();
        chunkRepository.updateEmbeddings(ids, vectors);
        return;
      } catch (RuntimeException e) {
        if (!isRateLimited(e) || attempt >= maxAttemptsPerBatch) {
          throw e;
        }
        Duration wait = extractSuggestedWait(e).orElse(rateLimitBackoff);
        log.info(
            "Embedding rate-limited (attempt {}/{}); backing off {} ms before retrying a batch of {} chunk(s)",
            attempt,
            maxAttemptsPerBatch,
            wait.toMillis(),
            batch.size());
        sleep(wait);
        attempt++;
      }
    }
  }

  // A rate limit surfaces as an HTTP 429 whose text bubbles up through the wrapped exception chain.
  // Match on the message rather than a Spring AI exception type so this stays decoupled from the
  // provider SDK — the same 429 signal reads identically whatever wraps it.
  static boolean isRateLimited(Throwable throwable) {
    for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
      String message = cause.getMessage();
      if (message != null) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("429")
            || lower.contains("too many requests")
            || lower.contains("rate limit")) {
          return true;
        }
      }
      if (cause.getCause() == cause) {
        break; // defend against a self-referential cause cycle
      }
    }
    return false;
  }

  static Optional<Duration> extractSuggestedWait(Throwable throwable) {
    for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
      String message = cause.getMessage();
      if (message != null) {
        Matcher matcher = SUGGESTED_WAIT.matcher(message);
        if (matcher.find()) {
          double value = Double.parseDouble(matcher.group(1));
          boolean millis = matcher.group(2).equalsIgnoreCase("ms");
          long waitMillis = Math.round(millis ? value : value * 1000);
          Duration wait = Duration.ofMillis(Math.max(1, waitMillis));
          return Optional.of(wait.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : wait);
        }
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return Optional.empty();
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new EmbeddingException("Interrupted while backing off for an embedding rate limit", e);
    }
  }

  record BackfillResult(int chunksEmbedded, int documentsTouched) {}
}
