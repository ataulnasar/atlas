# docker

Local development and reference deployment infrastructure for Atlas v1.

## Contents

- `docker-compose.yml` — local dev stack: PostgreSQL 16 + pgvector and `atlas-core`
- `docker-compose.prod.yml` — production-leaning profile: pinned images, no host-published
  database, required secrets, healthchecks, resource limits, and the built UI behind nginx
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

## Deploy (production profile)

`docker-compose.prod.yml` is a single-host production-leaning profile. It **requires three
secrets** and refuses to start without them. Set all three in `docker/.env` **before** booting:

```dotenv
# docker/.env — REQUIRED by the prod profile (no defaults, no keyless fallback):
POSTGRES_PASSWORD=…          # the database password
ATLAS_API_KEY=…              # Atlas's own door key (the X-API-Key clients must send)
SPRING_AI_OPENAI_API_KEY=…   # the OpenAI credential (embeddings + generation)
```

Compose checks these **one at a time**, so a missing one aborts with a single-variable error
(`… is required in production`) — set all three up front to avoid a boot-fix-boot loop. Then, from
this directory (so `${VARS}` interpolate from `./.env`):

```
docker compose -f docker-compose.prod.yml up -d --build
```

> If the dev stack is running it already holds ports 8080/80 — `docker compose down` it first.

**How it hardens the dev stack:**

- **Pinned images.** Postgres is pinned by immutable digest (`pgvector/pgvector:pg16@sha256:…`);
  the UI/build base images are pinned to minor tags. `restart: unless-stopped` on every service.
- **Database not exposed.** `postgres` has no `ports:` — it is reachable only on the internal
  compose network. Only `atlas-core` (`:8080`) and the UI (`:80`) are published to the host.
- **Configurable host ports.** The UI publishes `${ATLAS_UI_PORT:-80}:80` and atlas-core publishes
  `${ATLAS_CORE_PORT:-8080}:8080` — both **host-side only** (the containers always listen on 80 /
  8080 internally, and nginx proxies `/api` to the internal `atlas-core:8080` regardless of the
  host mapping). If a host port is already taken (a real prod boot hit `:80`), set `ATLAS_UI_PORT`
  / `ATLAS_CORE_PORT` in `.env` — e.g. `ATLAS_UI_PORT=8888` — and reach the app there
  (`http://<host>:8888`, API at `http://<host>:8888/api`).
- **Secrets are required, no keyless fallback.** `POSTGRES_PASSWORD`, `ATLAS_API_KEY`, and
  `SPRING_AI_OPENAI_API_KEY` use compose's `${VAR:?err}` form, so `up` **aborts loudly** if any
  is unset or empty. (Keyless auth is a dev-only affordance and is intentionally impossible here.)
  Set them in `docker/.env`; they are never defaulted.
- **Healthchecks on all three services.** `postgres` uses `pg_isready`; `atlas-core` curls the
  actuator endpoint it exposes (`/actuator/health` — the `eclipse-temurin:21-jre` image ships
  `curl`, so no extra install); the UI checks nginx with `wget`. `atlas-ui` waits on
  `atlas-core: service_healthy`, which waits on `postgres: service_healthy`.
- **Resource limits.** Sensible per-service `deploy.resources.limits` (postgres 512M/1 CPU,
  atlas-core 1G/2 CPU, UI 128M/0.5 CPU) — tune for your host.

**UI serving.** The built React app is served by a small **nginx** service that also
reverse-proxies `/api` to `atlas-core`, so the browser talks to one same-origin host (no CORS).
This was chosen over baking the UI into the Java image (Spring static resources): the UI and API
then build and scale independently, and the `atlas-core` image is identical to dev. See
`../atlas-ui/Dockerfile` and `../atlas-ui/nginx.conf`.

### Backup and restore

State lives in two named volumes (project `atlas-prod`): `atlas-prod_postgres-data` (the
database) and `atlas-prod_atlas-core-storage` (uploaded source documents). Back up both.

```bash
# --- Backup ---
# Database (custom-format dump):
docker compose -f docker-compose.prod.yml exec -T postgres \
  pg_dump -U atlas -d atlas -Fc > atlas-db-$(date +%Y%m%d).dump
# Uploaded documents (tar the storage volume):
docker run --rm -v atlas-prod_atlas-core-storage:/data -v "$PWD":/backup alpine \
  tar czf /backup/atlas-storage-$(date +%Y%m%d).tgz -C /data .

# --- Restore (into a running stack) ---
# Database:
docker compose -f docker-compose.prod.yml exec -T postgres \
  pg_restore -U atlas -d atlas --clean --if-exists < atlas-db-YYYYMMDD.dump
# Uploaded documents:
docker run --rm -v atlas-prod_atlas-core-storage:/data -v "$PWD":/backup alpine \
  sh -c 'cd /data && tar xzf /backup/atlas-storage-YYYYMMDD.tgz'
```

(The `-U atlas -d atlas` above assume the default `POSTGRES_USER`/`POSTGRES_DB`; adjust if you
overrode them in `.env`.)

## Status

Both dev services are real: `postgres` runs pgvector/pgvector:pg16 with a healthcheck and a
named volume, and `atlas-core` builds from `../atlas-core/Dockerfile`. The production profile
(`docker-compose.prod.yml`) adds pinned images, an unexposed database, required secrets,
per-service healthchecks and resource limits, and the built UI served by nginx.
