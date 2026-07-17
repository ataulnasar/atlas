package com.atlas.core.document;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
class DocumentUploadService {

  private final DocumentRepository documentRepository;
  private final UploadProperties uploadProperties;

  DocumentUploadService(DocumentRepository documentRepository, UploadProperties uploadProperties) {
    this.documentRepository = documentRepository;
    this.uploadProperties = uploadProperties;
  }

  Document upload(MultipartFile file) {
    SupportedContentType.fromMimeType(file.getContentType())
        .orElseThrow(() -> new UnsupportedDocumentTypeException(file.getContentType()));

    long maxBytes = uploadProperties.maxFileSize().toBytes();
    if (file.getSize() > maxBytes) {
      throw new DocumentTooLargeException(file.getSize(), maxBytes);
    }

    String contentHash = sha256Hex(file);

    try {
      return documentRepository.insertPending(file.getOriginalFilename(), contentHash);
    } catch (DuplicateKeyException e) {
      Document existing = documentRepository.findByContentHash(contentHash).orElseThrow(() -> e);
      throw new DuplicateDocumentException(existing.id());
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
