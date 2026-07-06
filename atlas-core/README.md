# atlas-core

Java/Spring Boot service implementing the Atlas RAG pipeline: document ingestion, chunking,
embedding, vector retrieval, and grounded generation.

## Stack

- Java 21, Spring Boot 3.x
- Maven (multi-module if/when split by concern: `ingestion`, `retrieval`, `api`)
- Vector store: TBD (pluggable — pgvector / Qdrant / OpenSearch)
- LLM provider: pluggable via a thin client abstraction

## Structure

```
src/main/java/com/atlas/core/   # application code
src/main/resources/             # application.yml, prompt templates
src/test/java/com/atlas/core/   # unit + integration tests
```

## Running locally

```
./mvnw spring-boot:run
```

Requires the local dependencies in `../docker` (vector store, object storage) to be running.

## Status

Scaffold only — v1 implementation in progress.
