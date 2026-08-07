# datasets

Golden Q&A / document fixture sets used by evals in `../evals`.

Each full dataset (Phase 4 onward) should be a directory containing:
- `questions.jsonl` — one eval case per line: `{"query": ..., "expected_answer": ..., "expected_sources": [...]}`
- `documents/` — the source documents the questions are grounded in (or a pointer to where they live)

## `mini-golden.json`

A 5-question seed for **Phase 2 retrieval tuning** against the EU digital-regulation demo
corpus (`corpus/manifest.json`) — one each of keyword / semantic / cross-document / hard /
unanswerable. It predates the Python harness ("no tooling yet"): with the docker stack up and
the corpus ingested, run the manual spot-check

```
./atlas-evals/mini-golden-check.sh          # needs curl + jq; ATLAS_BASE_URL overrides the target
```

which reports, per search endpoint, hit@5 + the first-expected-document rank for each answerable
question, and the top score for the unanswerable one. These 5 questions **seed the full Phase 4
golden dataset**, which will re-express them in the `questions.jsonl` + `documents/` convention
above (keep the ids stable).
