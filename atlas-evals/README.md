# atlas-evals

Python harness for evaluating `atlas-core`'s retrieval and generation quality.

## Stack

- Python 3.11+
- `pytest`-based eval runner (or a lightweight custom runner — TBD)
- Calls `atlas-core` over its HTTP API; no in-process Java dependency

## Structure

```
evals/       # eval definitions (retrieval precision/recall, answer faithfulness, etc.)
datasets/    # fixture Q&A / golden-document sets used by evals
```

## Running locally

```
uv sync
uv run -m evals.run --target http://localhost:8080
```

## Testing and linting

```
uv run pytest
uv run ruff check .
```

## Status

Scaffold only — eval suite in progress. Results are written to `results/` (git-ignored).
