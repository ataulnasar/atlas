# Architecture Decision Records

Decisions that shape Atlas v1, recorded so the reasoning survives the code. Format: lightweight
[MADR-ish](https://adr.github.io/) — Status, Context, Decision, Consequences.

| ADR | Title | Status |
| --- | ----- | ------ |
| [0001](0001-java-21.md) | Java 21 as the platform baseline | Accepted |
| [0002](0002-spring-boot-and-spring-ai.md) | Spring Boot 3.4+ with Spring AI for LLM integration | Accepted |
| [0003](0003-postgresql-pgvector.md) | PostgreSQL 16 + pgvector as the single datastore | Accepted |
| [0004](0004-python-eval-harness.md) | Python 3.12 evaluation harness, separate from the core | Accepted |
| [0005](0005-docker-compose-first.md) | Docker Compose first; no Kubernetes in v1 | Accepted |
| [0006](0006-v1-exclusions.md) | Explicit v1 exclusions | Accepted |
| [0007](0007-monorepo-http-boundaries.md) | Monorepo with HTTP boundaries between modules | Accepted |

A new ADR is added when a decision is (a) hard to reverse, (b) likely to be questioned later, or
(c) a deliberate deviation from common practice. Superseded ADRs are kept and marked, not deleted.
