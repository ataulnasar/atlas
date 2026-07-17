# ADR 0007 — Monorepo with HTTP boundaries between modules

**Status:** Accepted (2026-07)

## Context

Atlas has three deliverables in two languages plus infrastructure and docs: `atlas-core` (Java),
`atlas-evals` (Python), `atlas-fde` (playbooks), `docker`, `docs`. They could live in separate
repositories, or in one repository with either shared code or hard boundaries.

## Decision

One repository; modules coupled **only over HTTP**. `atlas-evals` never imports, links, or
introspects `atlas-core` — it is a client of the running service, full stop. `atlas-fde` is
documentation and templates, not a running component.

## Rationale

- **One repo:** a single clone gives the whole story (code, evals, playbooks, plan) — which is
  the point for both the quickstart and anyone assessing the project. Solo maintenance across
  three repos means triple the CI, versioning, and release ceremony for zero benefit at this
  scale.
- **HTTP-only coupling:** the eval harness measuring the system through its public API is what
  makes the measurements honest — it can't cheat by reaching into internals, and anything a
  metric needs (scores, citations, latency) must be exposed properly. It also makes the harness
  reusable against any deployed instance during FDE sign-off (see ADR 0004).
- **No shared code between languages:** no generated clients, no shared schema packages in v1.
  The API surface is small enough that a hand-written typed client (httpx + Pydantic) is cheaper
  than codegen machinery.

## Consequences

- CI runs independent Java and Python lanes from one workflow; a failure in either blocks merge.
- API changes must be made backward-compatibly or coordinated in the same PR — the compensating
  virtue of the monorepo is that cross-boundary changes are atomic and reviewable in one diff.
- Versioning is unified: one tag (`v1.0`) versions the platform, not three artifacts.
- If the API grows past hand-maintained-client size, an OpenAPI spec + generated client becomes
  a v2 ADR.
