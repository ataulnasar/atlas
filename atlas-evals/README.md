# atlas-evals

Python harness for evaluating `atlas-core`'s retrieval and generation quality. It is an external
HTTP client of `atlas-core` — no in-process Java dependency (see [ADR 0007](../docs/adr/0007-monorepo-http-boundaries.md)).

## Stack

- Python 3.12+, managed with [uv](https://docs.astral.sh/uv/).
- [Pydantic v2](https://docs.pydantic.dev/) models, [Typer](https://typer.tiangolo.com/) CLI,
  [httpx](https://www.python-httpx.org/) client.
- Quality gate: `ruff` (lint), `mypy` (types, strict-ish), `pytest`.

## Layout

```
src/atlas_evals/
  cli.py           # `atlas-eval` Typer entrypoint
  client.py        # typed httpx client for the Atlas HTTP API
  errors.py        # AtlasApiError (status + body)
  models/
    golden.py      # golden evaluation dataset schema
    api.py         # response/request models mirroring atlas-core's JSON contract
datasets/          # golden Q&A / expected-source sets (mini-golden.json today)
tests/             # unit tests (httpx MockTransport — no live server needed)
```

## Install & run

```bash
uv sync
uv run atlas-eval --help
uv run atlas-eval version
```

## API client

`AtlasClient` wraps the Atlas HTTP API with typed Pydantic responses:

```python
from atlas_evals.client import AtlasClient

with AtlasClient(base_url="http://localhost:8080") as atlas:
    hits = atlas.search_hybrid("what are the tasks of the data protection officer?", top_k=5)
    answer = atlas.chat("what are the tasks of the data protection officer?")
```

- **Base URL / timeout** are configurable (defaults: `ATLAS_BASE_URL` or `http://localhost:8080`, 30s).
- **Auth**: sends `X-API-Key` from `ATLAS_API_KEY` when set; omitted otherwise (keyless-dev servers
  accept the absent header).
- **Errors**: non-2xx raises `AtlasApiError` carrying the HTTP `status_code` and parsed `body`.
- **SSE streaming is out of scope** — evals use the synchronous `POST /api/chat` endpoint. The
  browser UI is the streaming consumer.

## Run, report, compare

```bash
# Run a dataset against a live Atlas (reads ATLAS_API_KEY from env when set):
uv run atlas-eval run --dataset demo-golden --base-url http://localhost:8080

# Render a report (Markdown, or --html):
uv run atlas-eval report results/run-<timestamp>.json --out report.md
uv run atlas-eval report results/run-<timestamp>.json --html --out report.html

# Compare two runs; exit 1 if any gated metric regressed beyond --tolerance:
uv run atlas-eval compare baseline.json candidate.json --out compare.md
```

The v1 baseline report lives at [`docs/eval-report-baseline.md`](../docs/eval-report-baseline.md).

## CI smoke & live smoke

Two layers, deliberately separated because CI has no OpenAI key and no running Atlas:

- **Harness smoke (every PR, no external calls).** `make smoke` (i.e. `scripts/ci_smoke.py`) runs
  the whole pipeline — `run → results schema → report → compare` — against an **in-process mock
  Atlas** (`atlas_evals.testing.mock_atlas`) serving canned responses for the 4-question
  [`datasets/ci-smoke.json`](datasets/ci-smoke.json), then compares the fresh run against a committed
  baseline (`tests/fixtures/ci_smoke_baseline.json`) and asserts exit codes. It proves the pipeline
  end-to-end with no keys, cost, or Docker, and is wired as a step in the python CI lane.

- **Live smoke (manual / pre-release, NOT in CI).** `make smoke-live` runs the real 6-question
  `mini-golden` dataset against a running stack. It needs a running Atlas and, if the server has
  auth enabled, `ATLAS_API_KEY` in your environment (the client picks it up); it also costs OpenAI
  tokens. Pass the key without persisting it:

  ```bash
  read -rs ATLAS_API_KEY && export ATLAS_API_KEY   # typed silently, this shell only
  make smoke-live                                  # or: ATLAS_BASE_URL=http://host:8080 make smoke-live
  unset ATLAS_API_KEY
  ```

**Regression gating contract** (for anyone wiring a full CI gate against a persisted baseline):
`atlas-eval compare <baseline> <candidate>` exits **0** when clean, **1** when any gated aggregate
metric (retrieval doc/page hit + MRR; chat citation accuracy, page-hit, false-abstention,
abstention) regresses by more than `--tolerance` (default 0.02), and **2** on a `schema_version`
mismatch. Latency is reported but never gated.

## Quality gate

```bash
uv run ruff check .
uv run mypy
uv run pytest
make smoke          # harness smoke (mock Atlas)
# or all of the above at once:
make check
```

## Status

Phase 4 is complete: typed models + API client, the golden dataset schema and the 30-question demo
dataset, deterministic retrieval/chat metrics, the `run`/`report`/`compare` commands, and the CI
harness smoke. Run results are written to `results/` (git-ignored). Deferred to v1.1 (see
docs/plan.md): Ragas/DeepEval integration, and citation precision/recall metrics (motivated by the
baseline report's failure-evidence findings).
