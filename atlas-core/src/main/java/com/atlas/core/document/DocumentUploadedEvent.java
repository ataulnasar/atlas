package com.atlas.core.document;

import java.util.UUID;

/**
 * Published once a document row and its file are both durably committed. Consumed by {@link
 * IngestionProcessor} via {@code @TransactionalEventListener(phase = AFTER_COMMIT)} — never handled
 * directly, so processing can never start against a transaction that later rolls back.
 */
record DocumentUploadedEvent(UUID documentId) {}
