package com.atlas.core.document;

import com.atlas.core.document.LocalFileStorageService.StoredFile;
import com.atlas.core.ingestion.ChunkCandidate;
import com.atlas.core.ingestion.ChunkingService;
import com.atlas.core.ingestion.DocumentParser;
import com.atlas.core.ingestion.DocumentParserRegistry;
import com.atlas.core.ingestion.ParsedDocument;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private final DocumentRepository documentRepository;
  private final ChunkRepository chunkRepository;
  private final LocalFileStorageService fileStorageService;
  private final DocumentParserRegistry parserRegistry;
  private final ChunkingService chunkingService;

  IngestionProcessor(
      DocumentRepository documentRepository,
      ChunkRepository chunkRepository,
      LocalFileStorageService fileStorageService,
      DocumentParserRegistry parserRegistry,
      ChunkingService chunkingService) {
    this.documentRepository = documentRepository;
    this.chunkRepository = chunkRepository;
    this.fileStorageService = fileStorageService;
    this.parserRegistry = parserRegistry;
    this.chunkingService = chunkingService;
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
      List<ChunkCandidate> chunks = chunkingService.chunk(parsed);

      // A zero-chunk document must never reach READY: there would be nothing for retrieval to
      // find, and a silent "successful" ingestion of no content is more misleading than a FAILED
      // one a caller can act on.
      if (chunks.isEmpty()) {
        log.warn("Ingestion produced no chunks for document {}", documentId);
        markFailedSafely(documentId, "document produced no content");
        return;
      }

      chunkRepository.insertAll(documentId, chunks);
      documentRepository.markReady(documentId);
    } catch (Exception e) {
      log.warn("Ingestion failed for document {}", documentId, e);
      markFailedSafely(documentId, truncatedMessage(e));
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

  private String truncatedMessage(Exception cause) {
    String message =
        cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    return message.length() > MAX_ERROR_MESSAGE_LENGTH
        ? message.substring(0, MAX_ERROR_MESSAGE_LENGTH)
        : message;
  }
}
