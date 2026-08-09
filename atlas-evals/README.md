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

## Quality gate

```bash
uv run ruff check .
uv run mypy
uv run pytest
```

## Status

Package structure, typed models, and API client are in place. The eval runner (retrieval
precision/recall, answer faithfulness against `datasets/`) lands in the remaining Phase 4 cards;
results will be written to `results/` (git-ignored).
