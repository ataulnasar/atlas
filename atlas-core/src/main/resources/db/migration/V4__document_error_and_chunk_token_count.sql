-- Diagnostics for the FAILED status: why did ingestion fail for this document.
ALTER TABLE document
    ADD COLUMN error_message TEXT;

-- No rows exist in chunk yet (nothing writes to it until the async ingestion processor),
-- so this can be added NOT NULL directly with no backfill. Needed for context-window
-- budgeting when assembling RAG context in Phase 3.
ALTER TABLE chunk
    ADD COLUMN token_count INTEGER NOT NULL;
