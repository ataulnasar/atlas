package com.atlas.core.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class LocalFileStorageServiceTest {

  @TempDir private Path storageRoot;

  private LocalFileStorageService storageService;

  @BeforeEach
  void setUp() {
    storageService = new LocalFileStorageService(new StorageProperties(storageRoot.toString()));
  }

  @Test
  void storesFileUnderADocumentIdFolderWithAGeneratedName() throws IOException {
    UUID documentId = UUID.randomUUID();
    MockMultipartFile file =
        new MockMultipartFile(
            "file",
            "report.pdf",
            "application/pdf",
            "pdf content".getBytes(StandardCharsets.UTF_8));

    Path storedPath = storageService.store(documentId, SupportedContentType.PDF, file);

    assertThat(storedPath).startsWith(storageRoot);
    assertThat(storedPath.getParent().getFileName().toString()).isEqualTo(documentId.toString());
    assertThat(storedPath.getFileName().toString()).isEqualTo("original.pdf");
    assertThat(Files.readString(storedPath)).isEqualTo("pdf content");
  }

  @Test
  void neverUsesTheOriginalFilenameOnDisk() throws IOException {
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    MockMultipartFile plainName =
        new MockMultipartFile(
            "file", "report.pdf", "application/pdf", "content-a".getBytes(StandardCharsets.UTF_8));
    MockMultipartFile weirdName =
        new MockMultipartFile(
            "file",
            "not the disk name !! .pdf",
            "application/pdf",
            "content-b".getBytes(StandardCharsets.UTF_8));

    Path firstPath = storageService.store(firstId, SupportedContentType.PDF, plainName);
    Path secondPath = storageService.store(secondId, SupportedContentType.PDF, weirdName);

    assertThat(firstPath.getFileName().toString()).isEqualTo("original.pdf");
    assertThat(secondPath.getFileName().toString()).isEqualTo("original.pdf");
  }

  @Test
  void resolvesNormalSegmentsWithinTheStorageRoot() {
    Path resolved = storageService.resolveWithinRoot("some-document-id", "original.txt");

    assertThat(resolved).isEqualTo(storageRoot.resolve("some-document-id").resolve("original.txt"));
  }

  @Test
  void rejectsSegmentsThatWouldEscapeTheStorageRootViaDotDot() {
    assertThatThrownBy(() -> storageService.resolveWithinRoot("..", "..", "etc", "passwd"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("escapes the storage root");
  }

  @Test
  void rejectsASingleSegmentEncodingATraversalSequence() {
    assertThatThrownBy(() -> storageService.resolveWithinRoot("../../../../etc/passwd"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("escapes the storage root");
  }
}
