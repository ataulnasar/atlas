package com.atlas.core.document;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
class DocumentUploadService {

  private final DocumentRepository documentRepository;
  private final ChunkRepository chunkRepository;
  private final UploadProperties uploadProperties;
  private final LocalFileStorageService fileStorageService;
  private final ApplicationEventPublisher eventPublisher;

  DocumentUploadService(
      DocumentRepository documentRepository,
      ChunkRepository chunkRepository,
      UploadProperties uploadProperties,
      LocalFileStorageService fileStorageService,
      ApplicationEventPublisher eventPublisher) {
    this.documentRepository = documentRepository;
    this.chunkRepository = chunkRepository;
    this.uploadProperties = uploadProperties;
    this.fileStorageService = fileStorageService;
    this.eventPublisher = eventPublisher;
  }

  // The insert (or FAILED-retry reset) and the file write must succeed or fail together: a
  // storage failure after either would otherwise leave a row whose content_hash permanently
  // blocks re-uploading the same content (the unique index would 409 it forever). An orphaned
  // file left behind by a rolled-back insert is comparatively harmless.
  @Transactional
  Document upload(MultipartFile file) {
    SupportedContentType contentType =
        SupportedContentType.fromMimeType(file.getContentType())
            .orElseThrow(() -> new UnsupportedDocumentTypeException(file.getContentType()));

    long maxBytes = uploadProperties.maxFileSize().toBytes();
    if (file.getSize() > maxBytes) {
      throw new DocumentTooLargeException(file.getSize(), maxBytes);
    }
    validateNotEmpty(file, contentType);

    String contentHash = sha256Hex(file);

    Document document;
    try {
      document = documentRepository.insertPending(file.getOriginalFilename(), contentHash);
    } catch (DuplicateKeyException e) {
      Document existing = documentRepository.findByContentHash(contentHash).orElseThrow(() -> e);
      document =
          retryIfFailed(existing).orElseThrow(() -> new DuplicateDocumentException(existing.id()));
    }

    fileStorageService.store(document.id(), contentType, file);

    // Deferred to AFTER_COMMIT by IngestionProcessor's listener — publishing here (inside the
    // transaction) is what makes that deferral possible, not a direct/@Async call from this
    // method. See DocumentUploadedEvent.
    eventPublisher.publishEvent(new DocumentUploadedEvent(document.id()));

    return document;
  }

  // A FAILED row's content_hash still occupies the unique index, so re-uploading identical bytes
  // hits the same DuplicateKeyException path as a true duplicate. Reset-in-place lets that retry
  // succeed under the SAME document id rather than forcing the caller to alter the bytes just to
  // get a fresh row. Returns empty if the row isn't FAILED, or if it was FAILED but a concurrent
  // retry already claimed it (resetFailedToPending's WHERE guard lost the race) — either way the
  // caller falls back to the ordinary duplicate rejection.
  private Optional<Document> retryIfFailed(Document existing) {
    if (existing.status() != DocumentStatus.FAILED) {
      return Optional.empty();
    }
    if (!documentRepository.resetFailedToPending(existing.id())) {
      return Optional.empty();
    }
    chunkRepository.deleteByDocumentId(existing.id());
    return documentRepository.findById(existing.id());
  }

  private void validateNotEmpty(MultipartFile file, SupportedContentType contentType) {
    if (file.isEmpty()) {
      throw new EmptyDocumentException("Uploaded file is empty");
    }
    // PDF/DOCX are left to the parser: whether they carry usable content is a parsing-time
    // question, not something worth (mis-)guessing from raw bytes at upload time.
    if (contentType == SupportedContentType.TXT && isBlank(file)) {
      throw new EmptyDocumentException("Uploaded text file contains no content");
    }
  }

  private boolean isBlank(MultipartFile file) {
    try {
      return new String(file.getBytes(), StandardCharsets.UTF_8).isBlank();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read uploaded file", e);
    }
  }

  private String sha256Hex(MultipartFile file) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(file.getBytes());
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read uploaded file", e);
    }
  }
}
