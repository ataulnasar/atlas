# atlas-core

Java/Spring Boot service implementing the Atlas RAG pipeline: document ingestion, chunking,
embedding, vector retrieval, and grounded generation.

## Stack

- Java 21 (enforced at build time via the Maven Enforcer plugin), Spring Boot 3.x
- Maven (single module)
- Vector store: PostgreSQL 16 + pgvector (see `docs/adr` — not swappable in v1)
- LLM/embedding provider: pluggable via a thin client abstraction (OpenAI in v1)

## Structure

```
src/main/java/com/atlas/core/   # application code
src/main/resources/             # application.yml, prompt templates
src/test/java/com/atlas/core/   # unit + integration tests
```

## Running locally

Two options — see the root [Quickstart](../README.md#quickstart) for the full recipe:

- **All in Docker** (recommended): `docker compose -f ../docker/docker-compose.yml up -d`.
- **App on host, DB in Docker**: start just Postgres with
  `docker compose -f ../docker/docker-compose.yml up -d postgres`, then run the app on the host:

  ```
  ./mvnw spring-boot:run
  ```

  The app needs the Postgres dependency in `../docker` running. On the host, point
  `SPRING_DATASOURCE_PASSWORD` at the `POSTGRES_PASSWORD` you set in `docker/.env` (the built-in
  default is `atlas`), and set `ATLAS_STORAGE_PATH` to a writable directory (e.g. `./data/documents`).
