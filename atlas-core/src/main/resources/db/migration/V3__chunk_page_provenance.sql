-- No rows exist in chunk yet (nothing writes to it until the async ingestion processor
-- card), so these can be added NOT NULL directly with no backfill.
ALTER TABLE chunk
    ADD COLUMN start_page INTEGER NOT NULL,
    ADD COLUMN end_page INTEGER NOT NULL;
