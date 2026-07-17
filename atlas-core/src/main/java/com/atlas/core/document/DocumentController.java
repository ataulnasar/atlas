package com.atlas.core.document;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

// TODO(security): unauthenticated for now — every /api/** endpoint, including this
// one, must require the X-API-Key header once "Add API key authentication"
// (docs/plan.md, Phase 3) lands.
@RestController
@RequestMapping("/api/documents")
class DocumentController {

  private final DocumentUploadService uploadService;

  DocumentController(DocumentUploadService uploadService) {
    this.uploadService = uploadService;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  ResponseEntity<DocumentUploadResponse> upload(@RequestParam("file") MultipartFile file) {
    Document document = uploadService.upload(file);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new DocumentUploadResponse(document.id(), document.status()));
  }
}
