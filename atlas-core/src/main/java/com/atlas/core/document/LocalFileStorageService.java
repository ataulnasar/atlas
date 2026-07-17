package com.atlas.core.document;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
class LocalFileStorageService {

  private final Path storageRoot;

  LocalFileStorageService(StorageProperties storageProperties) {
    this.storageRoot = Path.of(storageProperties.path()).toAbsolutePath().normalize();
    try {
      Files.createDirectories(storageRoot);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to create storage root: " + storageRoot, e);
    }
  }

  /**
   * Stores the file under {@code storageRoot/documentId/original.<ext>}. The on-disk name is always
   * generated from the validated content type — never from the uploaded filename, which is
   * preserved only in the document row.
   */
  Path store(UUID documentId, SupportedContentType contentType, MultipartFile file) {
    String safeFileName = "original." + contentType.extension();
    Path targetPath = resolveWithinRoot(documentId.toString(), safeFileName);
    try {
      Files.createDirectories(targetPath.getParent());
      file.transferTo(targetPath);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to store uploaded file at " + targetPath, e);
    }
    return targetPath;
  }

  /**
   * Resolves {@code segments} under the storage root, rejecting anything that would escape it (e.g.
   * via {@code ..} segments). Defense in depth: today's only caller passes a generated UUID and an
   * enum-derived extension, never raw user input, but this guard makes that an invariant of the
   * service itself rather than an assumption about its callers.
   */
  Path resolveWithinRoot(String... segments) {
    Path candidate = storageRoot;
    for (String segment : segments) {
      candidate = candidate.resolve(segment);
    }
    Path normalized = candidate.normalize();
    if (!normalized.startsWith(storageRoot)) {
      throw new IllegalArgumentException("Resolved path escapes the storage root: " + normalized);
    }
    return normalized;
  }
}
