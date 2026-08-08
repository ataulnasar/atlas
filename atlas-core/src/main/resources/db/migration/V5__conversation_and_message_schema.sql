CREATE TABLE conversation (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE message (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    -- Stable per-conversation ordering, independent of created_at ties. The UNIQUE below also
    -- creates the btree index that backs "the last N messages ordered by seq".
    seq             INTEGER NOT NULL,
    role            TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
    content         TEXT NOT NULL,
    -- The assistant turn's Citation list (see Citation.java), serialized as JSON. NULL for user
    -- turns, which cite nothing.
    citations       JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (conversation_id, seq)
);
