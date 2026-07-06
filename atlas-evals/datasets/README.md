# datasets

Golden Q&A / document fixture sets used by evals in `../evals`.

Each dataset should be a directory containing:
- `questions.jsonl` — one eval case per line: `{"query": ..., "expected_answer": ..., "expected_sources": [...]}`
- `documents/` — the source documents the questions are grounded in (or a pointer to where they live)

No datasets committed yet — add one per eval scenario as `atlas-evals` is implemented.
