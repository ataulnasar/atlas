# Atlas operational runbook

For whoever operates an Atlas deployment — installing it, keeping it running, and diagnosing it
when it misbehaves. It assumes the **production profile** (`docker/docker-compose.prod.yml`); the
plain `docker/docker-compose.yml` is for local dev. You do not need to have written any Atlas code
to follow this.

Throughout, commands are run from the repository root unless noted, and the prod stack is addressed
with `docker compose -f docker/docker-compose.prod.yml` (aliased below as `dc`):

```bash
alias dc='docker compose -f docker/docker-compose.prod.yml'
```

> First stop for **any** problem: run the diagnostics — see [Troubleshooting](#troubleshooting).

---

## 1. Install

### Prerequisites

- **Docker** with the Compose v2 plugin (`docker compose version` works).
- **Git**, and enough disk for the images plus your corpus and its embeddings.
- For corpus ingestion: `curl` and `jq` on the host.
- An **OpenAI API key** (generation + embeddings) if you want the assistant to answer, not just
  retrieve.

### Secrets

The prod profile requires three values and **refuses to start** without them (compose `${VAR:?}`
aborts loudly). They live in **`docker/.env`** — copied from `docker/.env.example`, never committed
(it is gitignored). Create it once and fill it in by hand:

```bash
cp docker/.env.example docker/.env
# then edit docker/.env
```

| Variable | What it is | Notes |
|---|---|---|
| `POSTGRES_PASSWORD` | The database password. | Set before first boot — see the volume caveat below. |
| `ATLAS_API_KEY` | **Atlas's own door key.** Required as the `X-API-Key` header on every `/api/**` request. | Your choice of value. The UI, `corpus/ingest.sh`, and `atlas-eval` all send it. |
| `SPRING_AI_OPENAI_API_KEY` | **The OpenAI credential** Atlas uses to call the provider for embeddings and generation. | An OpenAI key (starts with `sk-`). |

**These are two different keys and are easy to confuse** — the single most common cause of a broken
deployment:

- `ATLAS_API_KEY` is the lock on Atlas's *own* front door. Clients prove they're allowed in by
  sending it. You invent this value.
- `SPRING_AI_OPENAI_API_KEY` is Atlas's credential to a *third party* (OpenAI). OpenAI issues it.

Putting the `sk-…` OpenAI key into `ATLAS_API_KEY` (or vice-versa) is a real, recurring mistake.
`atlas-eval doctor` explicitly warns when `ATLAS_API_KEY` looks like an `sk-` key — run it if in
doubt.

Optional: `ATLAS_UI_PORT` (host port for the UI ingress, default `80`) and
`ATLAS_PARSING_STRIP_LINE_PATTERNS` (deployment-specific page-noise filter — see
`atlas-fde/demo-vertical.md`).

### Boot

```bash
dc up -d --build
```

This builds the images, starts Postgres, waits for it to be healthy, starts `atlas-core` (Flyway
migrates the schema on startup), then starts the UI. Verify:

```bash
dc ps                                   # all services "running"/"healthy"
curl -fsS http://localhost:8080/actuator/health   # {"status":"UP"}
```

The app is served at `http://<host>:${ATLAS_UI_PORT:-80}` (the API is proxied at `/api`).

### First-corpus ingestion

Point the corpus scripts at the **authenticated** prod instance (they read the same
`ATLAS_API_KEY`):

```bash
# 1. Fetch the documents this deployment should know (the demo EU-regulation corpus; swap in
#    your own manifest for a customer corpus — see atlas-fde/demo-vertical.md).
./corpus/download.sh

# 2. Upload + ingest them, sending the door key so the authed /api/documents endpoint accepts them.
export ATLAS_BASE_URL=http://localhost:8080
export ATLAS_API_KEY="$(grep -E '^ATLAS_API_KEY=' docker/.env | cut -d= -f2-)"
./corpus/ingest.sh
```

Ingestion is asynchronous: documents go `PENDING → PROCESSING → READY`, and embeddings are written
after the text is chunked. Confirm the corpus landed:

```bash
curl -fsS -H "X-API-Key: $ATLAS_API_KEY" http://localhost:8080/api/admin/stats
# {"totalDocuments":N,"readyDocuments":N,"totalChunks":M,"chunksWithoutEmbedding":0}
```

`chunksWithoutEmbedding` should fall to `0` once embedding finishes. If it stays high, see the
backfill row in [Troubleshooting](#troubleshooting). (`atlas-eval doctor` reports all of this in one
command.)

---

## 2. Upgrade

Atlas has no separate migration step to run: **Flyway migrations execute automatically on
`atlas-core` startup.** So an upgrade is *pull → rebuild → restart*, and the schema moves forward as
the new container boots.

```bash
# Always back up first (see §3) — an upgrade may run migrations that change the schema.
git pull
dc up -d --build         # rebuilds changed images and recreates changed services; migrations run
dc ps                    # confirm healthy
curl -fsS http://localhost:8080/actuator/health
```

Notes:

- Only changed services are recreated; Postgres (a pinned image) is left running unless its pin
  changed. Your data volumes are untouched by a rebuild.
- Watch the startup logs for the Flyway summary (`Successfully applied N migrations … now at
  version vX`). A failed migration leaves the app down — restore from your pre-upgrade backup and
  investigate before retrying.

---

## 3. Backup and restore

Two volumes hold all state (project name `atlas-prod`):

- `atlas-prod_postgres-data` — the database (documents, chunks, embeddings, conversations).
- `atlas-prod_atlas-core-storage` — the uploaded source files.

Back up **both**. The procedure below was tested end-to-end (dump → simulated loss → restore →
verified row/file recovery).

### Backup

```bash
# Database — custom-format dump:
dc exec -T postgres pg_dump -U atlas -d atlas -Fc > atlas-db-$(date +%Y%m%d).dump

# Uploaded documents — tar the storage volume:
docker run --rm -v atlas-prod_atlas-core-storage:/data -v "$PWD":/backup alpine \
  tar czf /backup/atlas-storage-$(date +%Y%m%d).tgz -C /data .
```

### Restore

```bash
# Database — clean and reload into the running stack:
dc exec -T postgres pg_restore -U atlas -d atlas --clean --if-exists < atlas-db-YYYYMMDD.dump

# Uploaded documents:
docker run --rm -v atlas-prod_atlas-core-storage:/data -v "$PWD":/backup alpine \
  sh -c 'cd /data && tar xzf /backup/atlas-storage-YYYYMMDD.tgz'
```

`pg_restore --clean --if-exists` drops and recreates objects, so it is safe to run against a
populated database. The `-U atlas -d atlas` assume the default `POSTGRES_USER`/`POSTGRES_DB`; adjust
if you changed them. **Keep the DB dump and the storage tarball as a matched pair** — restoring a
database that references files the storage volume no longer has (or vice-versa) leaves citations
dangling.

---

## 4. Rollback

Rolling back **code** is easy; rolling back **the database is not**, because migrations are
forward-only (there are no down-migrations).

```bash
git checkout <previous-good-tag-or-sha>
dc up -d --build
```

The critical caveat: **if the version you are leaving ran a new migration, the older code may not
understand the current schema.** Rolling code back *across a migration boundary* requires restoring
the database to its pre-migration state as well:

1. Stop the stack: `dc down` (this does **not** delete the named volumes).
2. Check out the previous code and rebuild — but do **not** start yet if the schema has already
   advanced.
3. Restore the DB from the backup you took *before* the upgrade (§3).
4. Start the stack.

If you did not take a pre-upgrade backup and the schema has moved, you cannot cleanly roll the
database back — which is exactly why §2 says to back up first. When unsure whether a release crossed
a migration boundary, compare the Flyway version in the startup logs before and after.

---

## 5. Troubleshooting

**Run this first, on any symptom** — it checks env keys, reachability, auth, corpus state, and
generation in one shot, and prints remediation hints:

```bash
atlas-eval doctor --base-url http://localhost:8080 --fix-hints
```

(from `atlas-evals/`: `uv run atlas-eval doctor …`). It distinguishes a key mismatch (401) from an
unreachable server from a 5xx, flags the `sk-` key confusion and stray whitespace, and reports
document/chunk/unembedded counts. The table below covers what it points you at, drawn from real
incidents on this project.

| Symptom | Likely cause | Fix |
|---|---|---|
| **401** despite a key that looks correct | Whitespace in `ATLAS_API_KEY` (trailing newline/space), the value in `.env` drifting from what the container has, or a hand-typed mismatch | **Never retype the key** — source it from the file. Fix `.env`, then `dc up -d --force-recreate atlas-core` so the container picks up the new value. `doctor` flags the whitespace/shape cases. |
| `Bind for 0.0.0.0:80 failed: port is already allocated` | Another service on the host already owns port 80 | Set `ATLAS_UI_PORT=8888` (or any free port) in `.env` and `dc up -d`, **or** stop the process holding :80. |
| After a failed bind, `dc ps` shows the UI as plain `80/tcp` with no `0.0.0.0:…->80` mapping | The port publish never got programmed, so nothing external can reach it | `dc up -d --force-recreate atlas-ui` to reprogram the mapping. |
| `HTTP 000` / empty responses in the first seconds after `up` | You're hitting the app before it finished booting (JVM + Flyway) | Wait ~10s and retry; watch `dc ps` for `healthy`. The `atlas-core` healthcheck has a startup grace period for exactly this. |
| `password authentication failed for user "atlas"` after editing `.env` | The Postgres data volume was **initialized with the old password** and keeps it — `POSTGRES_PASSWORD` only takes effect on *first* init | Either change it inside the DB: `dc exec postgres psql -U atlas -c "ALTER USER atlas PASSWORD '<new>';"`, **or**, if the volume holds nothing you need, reset it: `dc down && docker volume rm atlas-prod_postgres-data && dc up -d`. |
| OpenAI **429 (TPM)** during embedding backfill of a large corpus | Provider rate limit | Just re-run the backfill (`POST /api/admin/embeddings/backfill`) — it resumes from committed progress, and the built-in backoff handles most cases without intervention. |
| App or DB unreachable; an unrelated container holds `:8080` or `:5432` | Port squatting by another project | `docker ps` to find the squatter; stop it, or re-port Atlas via `SERVER_PORT` / (dev) `POSTGRES_PORT` in `.env`. |
| **Local** `./mvnw verify` fails to start Testcontainers after a Docker Desktop update | A stale `~/.testcontainers.properties` pins a Docker socket that no longer exists | `rm ~/.testcontainers.properties` and re-run; it is regenerated against the current socket. |

---

## 6. Monitoring basics

**Health.** `GET /actuator/health` returns `{"status":"UP"}` when the app and its datasource are
healthy — this is what the compose healthcheck and `atlas-eval doctor` poll. Postgres has its own
`pg_isready` healthcheck; `dc ps` shows both.

**Logs are structured JSON** (one object per line). Follow them with `dc logs -f atlas-core`.

**The chat INFO line** — one per answered request — is the main operational signal. It looks like:

```
chat conversationId=… retrievalMode=hybrid retrieved=8 contextChunks=6 citations=4 \
  latencyMs[retrieve=310 assemble=5 generate=11800] \
  tokens[prompt=5300 completion=180 total=5480] estCostUsd=0.001374
```

Read it as:

- **`latencyMs[retrieve/assemble/generate]`** — the latency breakdown. `generate` (the LLM call)
  usually dominates; a spike in `retrieve` points at the DB/vector index, not the provider.
- **`tokens[prompt/completion/total]`** — real usage for the turn. A creeping `prompt` count means
  context assembly is packing in more chunks.
- **`estCostUsd`** — approximate USD for the turn, from real token usage at the configured prices.
  Sum it across turns to watch spend. (Answer text is logged only at DEBUG, never at INFO.)

**Correlation IDs.** Every request gets a correlation ID — reused from an incoming
`X-Correlation-Id` header or generated — returned on the response as `X-Correlation-Id` and stamped
on **every** log line for that request as `correlationId`. To trace one request end to end, grab the
`X-Correlation-Id` from the response and filter the logs:

```bash
dc logs atlas-core | grep '"correlationId":"<the-id>"'
```
