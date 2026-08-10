# Atlas roadmap

The post-v1 work, tracked as GitHub issues. Every item is motivated by a concrete finding from
building and measuring v1 — the baseline report (`docs/eval-report-baseline.md`), the
retrieval-quality investigation (`docs/retrieval-quality.md`), or operational experience — not a
generic wishlist. Each issue carries that context and, where it's a quality change, the baseline
number it has to beat.

The v1 backlog itself is in [`docs/plan.md`](docs/plan.md).

## v1.1 — near-term

| # | Item | Motivated by |
|---|------|--------------|
| [#7](https://github.com/ataulnasar/atlas/issues/7) | Keyword OR/quorum-semantics experiment | Keyword AND-semantics collapse page-hit to 0.19 and dilute hybrid below vector (0.67 vs 0.70). |
| [#6](https://github.com/ataulnasar/atlas/issues/6) | Citation precision/recall metrics | Citation-document 0.76 is a strict-subset floor; precision/recall would separate over-citation from wrong-citation. |
| [#12](https://github.com/ataulnasar/atlas/issues/12) | Per-document page scoring for cross-document eval entries | Cross-document entries score pages against the primary document only, under-measuring multi-source answers. |
| [#5](https://github.com/ataulnasar/atlas/issues/5) | Opt-in Ragas/DeepEval metrics | Descoped from v1 by decision; add LLM-graded faithfulness/relevance behind a flag, off the default gate. |
| [#11](https://github.com/ataulnasar/atlas/issues/11) | Match-centered search snippets | A chunk-head snippet hid the matching Article body and misled analysis (retrieval-quality §1). |
| [#9](https://github.com/ataulnasar/atlas/issues/9) | Async embedding backfill job | Backfill is a synchronous admin call; large corpora + rate limits make it long-running (ingest.sh already retries around it). |
| [#10](https://github.com/ataulnasar/atlas/issues/10) | Dedicated retrieval executor | Retrieval runs on request threads and vector/keyword run sequentially; no isolation or timeouts. |
| [#13](https://github.com/ataulnasar/atlas/issues/13) | Test-infra hardening | Order-dependent full-suite flakiness (folds [#3](https://github.com/ataulnasar/atlas/issues/3)). |

## v2 — larger, parked in v1 per [ADR 0006](docs/adr/0006-v1-exclusions.md)

| # | Item | Motivated by |
|---|------|--------------|
| [#4](https://github.com/ataulnasar/atlas/issues/4) | Cross-encoder re-ranking | Must beat vector's page-hit 0.70 / MRR 0.526 on `demo-golden` to earn its latency. |
| [#8](https://github.com/ataulnasar/atlas/issues/8) | Query rewriting for multi-turn | Both multi-turn golden questions abstain (page-hit 0.00) — follow-ups don't carry context into retrieval. |
