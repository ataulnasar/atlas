CREATE TABLE document (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    filename      TEXT NOT NULL,
    content_hash  TEXT NOT NULL,
    status        TEXT NOT NULL DEFAULT 'PENDING'
                      CHECK (status IN ('PENDING', 'PROCESSING', 'READY', 'FAILED')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Content hash drives duplicate detection: reject re-ingesting a document whose
-- content already exists rather than silently duplicating it.
CREATE UNIQUE INDEX ux_document_content_hash ON document (content_hash);

CREATE TABLE chunk (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id   UUID NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    chunk_index   INTEGER NOT NULL,
    content       TEXT NOT NULL,
    -- 1536 dimensions matches text-embedding-3-small, the v1 default embedding model
    -- (see docs/adr/0002-spring-boot-and-spring-ai.md and 0003-postgresql-pgvector.md).
    -- Nullable: chunks are inserted before the async embedding step populates this.
    embedding     vector(1536),
    content_tsv   tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, chunk_index)
);

-- HNSW over cosine distance: text-embedding-3-small vectors are normalized, and
-- cosine similarity is the provider-recommended metric for ranking.
CREATE INDEX idx_chunk_embedding_hnsw ON chunk USING hnsw (embedding vector_cosine_ops);

-- GIN index backs full-text search over chunk content for the keyword side of
-- hybrid retrieval (vector + FTS fused with RRF — see docs/adr/0003).
CREATE INDEX idx_chunk_content_tsv_gin ON chunk USING gin (content_tsv);
