# Atlas v1 Architecture Overview

## Pipeline

```
Source documents
      |
      v
 [ingest] --chunk--> [embed] --> [vector store]
                                       |
User query --embed--> [retrieve] <----+
      |
      v
 [generate] --grounded answer--> User
```

## Components

- **atlas-core** — owns ingestion, embedding, retrieval, and generation orchestration.
- **vector store** — pluggable (pgvector/Qdrant/OpenSearch); decision tracked in `docs/adr`.
- **atlas-evals** — external client of `atlas-core`'s API; measures retrieval and answer quality.

## v1 boundaries

Single-tenant, single-region, synchronous request/response. No streaming, no multi-tenant
isolation, no managed control plane — see root `README.md` for full scope notes.
