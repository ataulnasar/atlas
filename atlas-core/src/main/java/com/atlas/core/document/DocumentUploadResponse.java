package com.atlas.core.document;

import java.util.UUID;

public record DocumentUploadResponse(UUID id, DocumentStatus status) {}
