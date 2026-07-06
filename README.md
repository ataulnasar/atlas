# Atlas v1

**Atlas** is an open-source Retrieval-Augmented Generation (RAG) platform built on Java and
Spring, paired with a Python evaluation harness and a set of forward-deployed engineering (FDE)
playbooks for taking it into production at a customer site.

Atlas exists because most RAG stacks are prototyped in Python and then rebuilt from scratch to
meet enterprise requirements around observability, type safety, and deployment discipline. Atlas
starts from the JVM side of that gap: a Spring Boot service for ingestion, retrieval, and
generation, with a Python-based quality bar (`atlas-evals`) and a documented deployment process
(`atlas-fde`) so quality isn't just implicit in the code — it's measured and repeatable.

## What it is

- A Java/Spring Boot service that implements the core RAG pipeline: document ingestion, chunking,
  embedding, vector retrieval, and grounded generation.
- A Python evaluation harness that measures retrieval and generation quality against golden
  datasets, independent of the core service's implementation language.
- A set of FDE playbooks and templates that codify how a deployment engineer takes Atlas from
  zero to a live customer instance, with an eval-based sign-off gate before go-live.
- A monorepo, not a framework — `atlas-core`, `atlas-evals`, and `atlas-fde` are independently
  useful and loosely coupled, communicating over HTTP rather than shared code.

## What v1 includes

- **RAG pipeline** (`atlas-core`) — ingest → embed → retrieve → generate, exposed over a REST API.
- **Pluggable vector store** — starts with pgvector as the reference implementation; swappable
  per deployment (final choice per environment tracked in `docs/adr`).
- **Pluggable LLM/embedding provider** — abstracted behind a thin client interface in `atlas-core`.
- **Offline eval suite** (`atlas-evals`) — retrieval precision/recall and answer-faithfulness
  checks run against golden Q&A datasets, driven from Python against `atlas-core`'s HTTP API.
- **FDE playbooks** (`atlas-fde`) — onboarding runbook and deployment checklist template covering
  prerequisites, ingestion, eval sign-off, go-live, and rollback.
- **Local dev environment** (`docker`) — `docker-compose.yml` to run `atlas-core` and its vector
  store dependency locally with one command.
- **CI** — build and test checks for both the Java and Python modules on every push/PR.

## What is not in v1

These are explicit non-goals for this release, not oversights — they're candidates for v2+:

- **Multi-tenant isolation.** v1 assumes one deployment per customer, not a shared multi-tenant
  service.
- **Managed/hosted control plane.** There is no SaaS control plane, admin UI, or usage metering —
  Atlas is deployed and operated by whoever runs it.
- **UI/dashboard.** Interaction with Atlas is via its REST API; no end-user or admin frontend
  ships in v1.
- **Streaming responses.** Generation is synchronous request/response; token streaming is not
  wired up yet.
- **Online/production evals.** `atlas-evals` currently runs offline against golden datasets, not
  as a continuous production quality monitor.
- **Advanced retrieval strategies.** No hybrid search, re-ranking, or query rewriting in v1 — the
  pipeline is intentionally the simplest version that works, to establish a quality baseline first.

## Architecture

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

- **atlas-core** (Java/Spring Boot) owns the pipeline above and exposes it over HTTP.
- **vector store** is pluggable — pgvector is the local/reference default; the production choice
  per deployment is a documented decision (see `docs/adr`), not a hardcoded dependency.
- **atlas-evals** (Python) is an external client of `atlas-core` — it has no in-process dependency
  on the Java code, so it can evaluate any running instance, local or remote.
- **atlas-fde** is process and documentation, not a running component — it governs how the above
  gets deployed and validated at a customer site.

See [`docs/architecture/overview.md`](docs/architecture/overview.md) for more detail and
[`docs/adr`](docs/adr) for the reasoning behind key design decisions.

## Quickstart

> Placeholder — filled in as `atlas-core` and `docker` scaffolding are built out.

```bash
# 1. Clone
git clone https://github.com/<org>/atlas-v1.git
cd atlas-v1

# 2. Start local infra (vector store) and atlas-core
cp docker/.env.example docker/.env
docker compose -f docker/docker-compose.yml up -d

# 3. Run atlas-core directly instead, if iterating locally
cd atlas-core
./mvnw spring-boot:run

# 4. Run the eval suite against the running instance
cd ../atlas-evals
pip install -r requirements.txt
python -m evals.run --target http://localhost:8080
```

Full setup instructions live in each module's README: [`atlas-core`](atlas-core/README.md),
[`atlas-evals`](atlas-evals/README.md), [`docker`](docker/README.md).

## Roadmap

Beyond v1, in rough priority order:

- **Retrieval quality** — hybrid search, re-ranking, query rewriting.
- **Online evals** — continuous quality monitoring against live production traffic.
- **Streaming generation** — token-level streaming over the API.
- **Multi-tenant support** — shared-service deployment model.
- **UI** — a minimal admin/debugging frontend for ingestion status and query tracing.
- **Managed control plane** — hosted/managed offering, once the self-hosted model is proven out.

Roadmap items are tracked as GitHub issues; see the repository's issue tracker for current status.

## Repository layout

| Path          | Purpose                                                             |
|---------------|----------------------------------------------------------------------|
| `atlas-core`  | Java/Spring Boot RAG service — ingestion, retrieval, orchestration |
| `atlas-evals` | Python harness for offline/online RAG quality evaluation            |
| `atlas-fde`   | Forward-deployed engineering playbooks and deployment templates      |
| `docker`      | Local dev and reference deployment compose files                    |
| `docs`        | Architecture notes and Architecture Decision Records (ADRs)          |

## Contributing

Issues and pull requests are welcome. Since Atlas is early and its interfaces are still moving,
please open an issue to discuss substantial changes before submitting a PR.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
