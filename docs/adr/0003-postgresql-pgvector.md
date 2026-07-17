# ADR 0003 — PostgreSQL 16 + pgvector as the single datastore

**Status:** Accepted (2026-07)

## Context

Atlas needs relational storage (documents, chunks, conversations, eval metadata), vector
similarity search, and keyword full-text search. Options: a dedicated vector database (Qdrant,
Weaviate, Milvus, Pinecone) alongside Postgres, or Postgres with the pgvector extension doing
all three jobs.

## Decision

One PostgreSQL 16 instance with pgvector. Vector search via an HNSW index, keyword search via
`tsvector`/GIN, relational data in the same schema. No second datastore in v1.

## Rationale

- **One system to operate.** Atlas ships with FDE playbooks; every additional stateful service
  multiplies the install, backup, restore, and troubleshooting surface. A single Postgres is the
  most deployable possible answer, and Postgres is the database enterprise customers already
  know how to run.
- **Transactional consistency.** Chunks, their embeddings, and their FTS vectors live in one
  table — ingestion status transitions, duplicate detection, and hybrid search (vector + keyword
  fused with RRF) all work without cross-store synchronization.
- **Sufficient scale for v1.** Corpora in the tens of thousands of chunks are well within
  pgvector/HNSW comfort. Dedicated vector DBs earn their complexity at scales v1 explicitly does
  not target.

## The dimension coupling — stated honestly

The `vector(1536)` column ties the schema to `text-embedding-3-small`. Changing embedding model
or provider means a Flyway migration **and re-embedding the entire corpus** — embeddings from
different models are not comparable, so this is inherent to vector search, not a pgvector
limitation. Atlas therefore does **not** claim "swappable embedding provider" as a runtime
property: the provider is configurable at deployment time, and the schema/model pairing is a
per-deployment decision recorded at install (see `atlas-fde`).

## Consequences

- `docker compose up` yields the full stack with one stateful service; backup = `pg_dump`.
- Hybrid search is a SQL-level feature, demonstrable and testable with Testcontainers.
- A future dedicated vector store (if scale demands) becomes a v2+ ADR that supersedes this one,
  behind the existing repository interfaces.
