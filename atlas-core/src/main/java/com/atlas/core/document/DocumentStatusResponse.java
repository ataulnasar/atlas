package com.atlas.core.document;

import java.util.UUID;

public record DocumentStatusResponse(
    UUID id, String filename, DocumentStatus status, String errorMessage, int chunkCount) {}
