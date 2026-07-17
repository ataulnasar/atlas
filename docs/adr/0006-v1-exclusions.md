# ADR 0006 — Explicit v1 exclusions

**Status:** Accepted (2026-07)

## Context

Solo-built platforms die of scope creep. The strongest protection is making exclusions explicit,
public, and reasoned — so every "wouldn't it be cool if" has to argue against a written decision
instead of an empty space. This ADR is that decision. (What v1 *includes* is defined in the root
README and `docs/plan.md`.)

## Decision

The following are **not in v1**. Each is a roadmap candidate, tracked as a GitHub issue, not a
gap to be quietly filled.

| Exclusion | Why deferred | What v1 does instead |
| --- | --- | --- |
| Multi-tenancy | Isolation, quotas, and tenancy-aware retrieval triple the schema and security surface | One deployment per customer (matches the FDE model) |
| Kubernetes / Helm | See ADR 0005 | Docker Compose |
| OAuth2 / SSO | Identity integration is per-customer work, not platform work at this stage | Static API-key auth on all `/api/**` endpoints |
| Cross-encoder re-ranking | Must be justified by measurement, not fashion — it's a **benchmark hypothesis**: expected lift on hit rate/MRR over hybrid-only, to be proven with `atlas-eval compare` | Hybrid search (vector + FTS, RRF-fused) as the quality baseline |
| Query rewriting / agents | Same discipline: baseline first, measured additions later | Single-shot retrieval with tuned chunking/topK |
| Online / production evals | Requires traffic capture, sampling, and privacy handling | Offline evals against golden datasets, with CI smoke runs |
| OCR / scanned documents | Parsing quality rabbit hole | PDF (text-layer), DOCX, TXT parsers |
| Managed control plane / SaaS | Premature before the self-hosted model is proven | Self-hosted, playbook-driven deployment |
| Redis / message broker | Async needs at v1 scale are met in-process | Spring `@Async` ingestion pipeline with DB-backed status |

## Consequences

- Feature requests hitting this list get a link, not a debate.
- Re-ranking in particular carries its acceptance test with it: it enters v2 only with a
  before/after eval comparison attached.
- If an exclusion later proves wrong, the fix is a superseding ADR — visible reasoning either
  way.
