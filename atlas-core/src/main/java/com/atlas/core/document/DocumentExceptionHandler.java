package com.atlas.core.document;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice(basePackageClasses = DocumentController.class)
class DocumentExceptionHandler {

  @ExceptionHandler(UnsupportedDocumentTypeException.class)
  ResponseEntity<ApiError> handleUnsupportedType(UnsupportedDocumentTypeException e) {
    return ResponseEntity.badRequest()
        .body(new ApiError("unsupported_document_type", e.getMessage()));
  }

  @ExceptionHandler(DocumentTooLargeException.class)
  ResponseEntity<ApiError> handleTooLarge(DocumentTooLargeException e) {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(new ApiError("document_too_large", e.getMessage()));
  }

  // Defense in depth: fires only if a request slips past our own size check but still
  // exceeds the servlet-level ceiling (spring.servlet.multipart.max-file-size).
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<ApiError> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(new ApiError("document_too_large", "Uploaded file exceeds the maximum allowed size"));
  }

  @ExceptionHandler(DuplicateDocumentException.class)
  ResponseEntity<DuplicateDocumentError> handleDuplicate(DuplicateDocumentException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new DuplicateDocumentError(
                "duplicate_document", e.getMessage(), e.existingDocumentId()));
  }
}
