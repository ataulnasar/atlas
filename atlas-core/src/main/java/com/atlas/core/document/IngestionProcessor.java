package com.atlas.core.document;

import com.atlas.core.document.LocalFileStorageService.StoredFile;
import com.atlas.core.embedding.EmbeddingService;
import com.atlas.core.ingestion.ChunkCandidate;
import com.atlas.core.ingestion.ChunkingService;
import com.atlas.core.ingestion.DocumentParser;
import com.atlas.core.ingestion.DocumentParserRegistry;
import com.atlas.core.ingestion.ParsedDocument;
import com.atlas.core.ingestion.TextCleanupService;
import com.atlas.core.ingestion.TextCleanupService.CleanupResult;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Drives a document from PENDING through PROCESSING to READY or FAILED: load the stored file, parse
 * it, chunk it, persist the chunks. Triggered off {@link DocumentUploadedEvent} rather than a
 * direct call from the upload path — see that class for why.
 */
@Component
class IngestionProcessor {

  private static final Logger log = LoggerFactory.getLogger(IngestionProcessor.class);
  private static final int MAX_ERROR_MESSAGE_LENGTH = 500;
  private static final String EMBEDDING_FAILED_PREFIX = "embedding failed: ";

  private final DocumentRepository documentRepository;
  private final ChunkRepository chunkRepository;
  private final LocalFileStorageService fileStorageService;
  private final DocumentParserRegistry parserRegistry;
  private final TextCleanupService textCleanupService;
  private final ChunkingService chunkingService;
  private final ChunkEmbeddingService chunkEmbeddingService;
  // Injected via a provider because the EmbeddingService bean only exists when an API key is
  // configured (see EmbeddingConfig). Absent = keyless degraded mode: chunks stay NULL-embedded.
  private final ObjectProvider<EmbeddingService> embeddingServiceProvider;
  private final AtomicBoolean embeddingDisabledWarned = new AtomicBoolean(false);

  IngestionProcessor(
      DocumentRepository documentRepository,
      ChunkRepository chunkRepository,
      LocalFileStorageService fileStorageService,
      DocumentParserRegistry parserRegistry,
      TextCleanupService textCleanupService,
      ChunkingService chunkingService,
      ChunkEmbeddingService chunkEmbeddingService,
      ObjectProvider<EmbeddingService> embeddingServiceProvider) {
    this.documentRepository = documentRepository;
    this.chunkRepository = chunkRepository;
    this.fileStorageService = fileStorageService;
    this.parserRegistry = parserRegistry;
    this.textCleanupService = textCleanupService;
    this.chunkingService = chunkingService;
    this.chunkEmbeddingService = chunkEmbeddingService;
    this.embeddingServiceProvider = embeddingServiceProvider;
  }

  @Async(IngestionExecutorConfig.EXECUTOR_BEAN_NAME)
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  void onDocumentUploaded(DocumentUploadedEvent event) {
    process(event.documentId());
  }

  void process(UUID documentId) {
    try {
      if (!documentRepository.claimForProcessing(documentId)) {
        return; // already claimed/processed elsewhere, or not in a claimable state
      }

      StoredFile storedFile =
          fileStorageService
              .findStoredFile(documentId)
              .orElseThrow(
                  () ->
                      new IllegalStateException("No stored file found for document " + documentId));
      DocumentParser parser = parserRegistry.resolve(storedFile.contentType());
      ParsedDocument parsed = parser.parse(storedFile.path());

      CleanupResult cleanupResult = textCleanupService.clean(parsed);
      if (cleanupResult.strippedLineCount() > 0) {
        log.debug(
            "Stripped {} noise line(s) from document {}",
            cleanupResult.strippedLineCount(),
            documentId);
      }

      List<ChunkCandidate> chunks = chunkingService.chunk(cleanupResult.document());

      // A zero-chunk document must never reach READY: there would be nothing for retrieval to
      // find, and a silent "successful" ingestion of no content is more misleading than a FAILED
      // one a caller can act on.
      if (chunks.isEmpty()) {
        log.warn("Ingestion produced no chunks for document {}", documentId);
        markFailedSafely(documentId, "document produced no content");
        return;
      }

      chunkRepository.insertAll(documentId, chunks);

      if (!embedChunks(documentId)) {
        return; // embedding failed — already marked FAILED with the embedding-failed message
      }

      documentRepository.markReady(documentId);
    } catch (Exception e) {
      log.warn("Ingestion failed for document {}", documentId, e);
      markFailedSafely(documentId, truncatedMessage(messageOf(e)));
    }
  }

  /**
   * Embeds the document's freshly-inserted chunks before it goes READY. Returns {@code true} to
   * continue to READY (embedding succeeded, or is disabled in keyless mode), {@code false} if
   * embedding failed and the document was routed to FAILED instead.
   */
  private boolean embedChunks(UUID documentId) {
    EmbeddingService embeddingService = embeddingServiceProvider.getIfAvailable();
    if (embeddingService == null) {
      warnEmbeddingDisabledOnce();
      return true; // degraded mode: proceed to READY with NULL embeddings
    }
    try {
      chunkEmbeddingService.embedDocumentChunks(documentId, embeddingService);
      return true;
    } catch (Exception e) {
      log.warn("Embedding failed for document {}", documentId, e);
      markFailedSafely(documentId, truncatedMessage(EMBEDDING_FAILED_PREFIX + messageOf(e)));
      return false;
    }
  }

  private void warnEmbeddingDisabledOnce() {
    if (embeddingDisabledWarned.compareAndSet(false, true)) {
      log.warn("embedding disabled — no provider configured, chunks will not be searchable");
    }
  }

  // A FAILED transition must never itself throw — that would leave the document stuck in
  // PROCESSING forever with no way to retry or diagnose it.
  private void markFailedSafely(UUID documentId, String errorMessage) {
    try {
      documentRepository.markFailed(documentId, errorMessage);
    } catch (Exception markFailedFailure) {
      log.error("Failed to mark document {} as FAILED", documentId, markFailedFailure);
    }
  }

  private String messageOf(Exception cause) {
    return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
  }

  private String truncatedMessage(String message) {
    return message.length() > MAX_ERROR_MESSAGE_LENGTH
        ? message.substring(0, MAX_ERROR_MESSAGE_LENGTH)
        : message;
  }
}
