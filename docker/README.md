# docker

Local development and reference deployment infrastructure for Atlas v1.

## Contents (planned)

- `docker-compose.yml` — local stack: vector store, `atlas-core`, supporting services
- `atlas-core/Dockerfile` — production image for the Spring Boot service
- `.env.example` — template for required environment variables

## Usage

```
docker compose up -d
```

Bring up dependencies before running `atlas-core` locally via Maven, or run the full
stack (including `atlas-core`) once its Dockerfile is added.

## Status

Scaffold only — compose files and Dockerfiles to be added alongside `atlas-core`.
