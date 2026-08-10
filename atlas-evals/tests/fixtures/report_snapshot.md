# Atlas evaluation report

| Field | Value |
|---|---|
| Dataset | fixture-golden |
| Base URL | http://fixture |
| Engines | vector, keyword, hybrid, chat |
| top_k | 5 |
| Started | 2026-01-01T00:00:00+00:00 |
| Finished | 2026-01-01T00:05:00+00:00 |
| Questions | 4 |
| Errored | 0 (0%) |
| Schema version | 1 |

## Retrieval summary

| Engine | doc_hit | page_hit | MRR | n | mean_ms |
|---|---|---|---|---|---|
| vector | 1.00 | 1.00 | 1.000 | 3 | 0 |
| keyword | 0.33 | 0.33 | 0.333 | 3 | 0 |
| hybrid | 1.00 | 1.00 | 1.000 | 3 | 0 |

## Chat summary

| Metric | Value | Denominator |
|---|---|---|
| citation_document_accuracy | 0.67 | answerable questions with ≥1 citation |
| citation_page_hit_rate | 1.00 | all answerable questions |
| false_abstention_rate | 0.00 | all answerable questions (lower is better) |
| abstention_rate | 1.00 | unanswerable questions (higher is better) |
| mean latency (ms) | 0 | evaluated chat turns |
| total tokens | 480 | final chat turns (setup turns not counted) |
| estimated cost | $0.0003 | model gpt-5-mini-2025-08-07 @ static rates |

## Per-category breakdown

| category | n | vec_page | key_page | hyb_page | chat_cite_doc | chat_cite_page | abstain* |
|---|---|---|---|---|---|---|---|
| keyword | 1 | 1.00 | 1.00 | 1.00 | 1.00 | 1.00 | 0.00 |
| semantic | 1 | 1.00 | 0.00 | 1.00 | 1.00 | 1.00 | 0.00 |
| cross-document | 1 | 1.00 | 0.00 | 1.00 | 0.00 | 1.00 | 0.00 |
| unanswerable | 1 | — | — | — | — | — | 1.00 |

Retrieval columns are page_hit rates (blank where an engine did not run, e.g. unanswerables). `abstain*` is the false-abstention rate for answerable categories and the correct-abstention rate for `unanswerable`.

## Failure evidence: citation-document misses

Cross-document entries score **pages against the primary (first-listed) document only**; secondary documents are credited at the document level. The citation-document metric is a **strict subset check** (all cited docs must be expected), so it can penalize a legitimate secondary or neighbouring citation even when the answer is well grounded — see the failure-evidence section to adjudicate each case. (Dataset note: `cross_document_scoring`.)

| id | expected | cited | unexpected (cited∉expected) |
|---|---|---|---|
| x1 | nis2.pdf, dora.pdf | nis1.pdf, nis2.pdf | nis1.pdf |

## Per-question detail

| id | category | vec | key | hyb | chat_cites | flags |
|---|---|---|---|---|---|---|
| k1 | keyword | ✓✓ | ✓✓ | ✓✓ | 1 | — |
| s1 | semantic | ✓✓ | ·· | ✓✓ | 1 | — |
| x1 | cross-document | ✓✓ | ·· | ✓✓ | 2 | cite-doc✗ |
| u1 | unanswerable | — | — | — | 0 | — |

Retrieval marks are `doc``page` (`✓`/`·`); `—` where the engine did not run.

## Denominators & notes

- **Retrieval n = 3** (of 4): the 1 unanswerable questions skip retrieval (no expected document), so retrieval is scored over the answerable set only.
- **Multi-turn** questions count as answerable and DO run retrieval, but on the final question text alone — the retrieval endpoints are stateless (no conversation history), which can understate multi-turn retrieval. Their chat turn replays the setup turns on one conversation before the evaluated question.
- **Chat evaluated = 4** (all questions): `citation_document_accuracy` is over answerable questions with ≥1 citation; `citation_page_hit_rate` and `false_abstention_rate` are over all answerable questions; `abstention_rate` is over unanswerable questions.

- _Cross-document scoring: page metrics are against the primary (first-listed) expected document only; secondary documents are credited at the document level._
- _Abstention is a deterministic proxy: a chat turn counts as abstained iff it returned zero citations (the answer text is not inspected)._
