# Atlas v1 Architecture Overview

## Pipeline

```
Source documents
      |
      v
 [ingest] --chunk--> [embed] --> [vector store (pgvector) + full-text index (Postgres)]
                                       |
User query --embed--> [hybrid retrieve: vector + full-text, fused via RRF] <----+
      |
      v
 [generate] --SSE stream + citations--> Chat UI / API client
```

## Components

- **atlas-core** — owns ingestion, embedding, hybrid retrieval, and generation orchestration;
  streams responses over SSE and serves the minimal React chat UI's API calls behind API-key auth.
- **vector store** — PostgreSQL 16 + pgvector. Not swappable per deployment in v1: the embedding
  dimension couples the schema to the embedding model in use (decision tracked in `docs/adr`).
- **atlas-evals** — external client of `atlas-core`'s API; measures retrieval and answer quality.

## v1 boundaries

Single-tenant, single-region. No multi-tenant isolation, no managed control plane, no re-ranking
or query rewriting, no Kubernetes, no OAuth2 (API-key auth only) — see root `README.md` for full
scope notes.
