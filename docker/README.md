# docker

Local development and reference deployment infrastructure for Atlas v1.

## Contents

- `docker-compose.yml` — local stack: PostgreSQL 16 + pgvector and `atlas-core`
- `.env.example` — template for required environment variables; copy to `.env` and fill in
  (`.env` is gitignored — never commit real credentials)

## Usage

```
cp .env.example .env
# edit .env — at minimum set POSTGRES_PASSWORD
docker compose up -d
```

`postgres` exposes a `pg_isready` healthcheck; `atlas-core` waits on
`condition: service_healthy` before starting, so a plain `docker compose up -d` brings up
a working stack in dependency order. `atlas-core`'s `SPRING_DATASOURCE_*` variables are
derived from the `POSTGRES_*` values in `.env` — no separate datasource config is needed.

## Status

Both services are real: `postgres` runs pgvector/pgvector:pg16 with a healthcheck and a
named volume, and `atlas-core` builds from `../atlas-core/Dockerfile`.
