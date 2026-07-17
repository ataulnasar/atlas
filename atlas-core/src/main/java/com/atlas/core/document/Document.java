package com.atlas.core.document;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Document(
    UUID id,
    String filename,
    String contentHash,
    DocumentStatus status,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
