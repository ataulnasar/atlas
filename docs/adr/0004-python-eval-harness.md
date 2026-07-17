# ADR 0004 — Python 3.12 evaluation harness, separate from the core

**Status:** Accepted (2026-07)

## Context

RAG quality must be measured, not assumed: retrieval metrics (hit rate, MRR, expected-source
recall) and generation metrics (faithfulness, answer relevance). The harness could be written in
Java inside `atlas-core`, or in Python as a separate module.

## Decision

`atlas-evals` is a standalone Python 3.12 package (uv, Typer CLI, httpx + Pydantic client,
ruff/pytest/type-checking in CI) that evaluates a **running** Atlas instance over its HTTP API.

## Rationale

- **The evaluation ecosystem is Python.** Ragas, DeepEval, and the research they track are
  Python-first; reimplementing them in Java means permanently lagging the state of the art.
- **Independence is a feature.** Because the harness only speaks HTTP, it can evaluate any Atlas
  deployment — local, staging, or a customer instance during FDE sign-off — and would work
  unchanged against a hypothetical non-Java reimplementation. The eval suite is the contract;
  the core is an implementation.
- **Deliberate bilingualism.** Enterprise AI work is Java/Python polyglot in practice; Atlas
  models that honestly instead of pretending one language covers both jobs.

## Metric strategy

- **Deterministic metrics in CI** (hit rate, MRR, source recall, latency): no LLM judge, no
  flakiness, cheap enough to run on every PR against a small smoke dataset.
- **LLM-judged metrics opt-in** (Ragas/DeepEval faithfulness, relevance, context precision):
  run manually for reports and regression comparisons, never as a CI gate — non-determinism and
  cost make them unsuitable as blocking checks.

## Consequences

- Two toolchains in one repo: CI runs Java and Python lanes; contributors may only touch one.
- The HTTP boundary means evals can't inspect internals (e.g., raw retrieval scores must be
  exposed via the API if a metric needs them) — pressure that keeps the API honest.
- `atlas-eval run / report / compare` becomes the regression mechanism: two runs are comparable
  artifacts, and "did this change hurt quality?" has a command-line answer.
