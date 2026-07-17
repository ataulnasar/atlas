# ADR 0005 — Docker Compose first; no Kubernetes in v1

**Status:** Accepted (2026-07)

## Context

Atlas needs a reference deployment story for local development, the demo, and the FDE playbooks.
Kubernetes manifests/Helm charts are the enterprise default expectation; Docker Compose is the
simpler alternative.

## Decision

Docker Compose is the only supported deployment mechanism in v1: one file, two services
(`atlas-core`, Postgres+pgvector), health checks, `.env`-driven configuration. Kubernetes is an
explicit v2+ roadmap item.

## Rationale

- **The 15-minute quickstart is a v1 success criterion.** `git clone` → `docker compose up` →
  upload → chat → eval must work on a clean machine. Compose keeps that promise; Kubernetes
  makes the quickstart depend on a cluster the reader may not have.
- **v1's deployment model is single-instance per customer** (see ADR 0006 — no multi-tenancy).
  For one app container and one database, Kubernetes buys orchestration nobody asked for and an
  operational surface the FDE runbook would have to document.
- **Honest sequencing.** Shipping Helm charts before the application is proven inverts the risk:
  the hard problems in v1 are retrieval quality and eval discipline, not orchestration.

## Consequences

- The FDE runbook (install, upgrade, backup, restore, rollback) is written against Compose —
  shorter, testable on a laptop, and executable by a stranger.
- Scale-out (replicas, autoscaling, zero-downtime deploys) is out of scope until v2; acceptable
  for demo and single-team deployments.
- When Kubernetes arrives in v2, the Compose file remains as the local-dev path; the two serve
  different jobs and both stay.
