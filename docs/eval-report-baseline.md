# Atlas evaluation report

| Field | Value |
|---|---|
| Dataset | atlas-demo-golden |
| Base URL | http://localhost:8080 |
| Engines | vector, keyword, hybrid, chat |
| top_k | 5 |
| Started | 2026-08-10T02:00:56.175526+00:00 |
| Finished | 2026-08-10T02:09:51.845222+00:00 |
| Questions | 30 |
| Errored | 0 (0%) |
| Schema version | 1 |

## Retrieval summary

| Engine | doc_hit | page_hit | MRR | n | mean_ms |
|---|---|---|---|---|---|
| vector | 0.96 | 0.70 | 0.526 | 27 | 327 |
| keyword | 0.48 | 0.19 | 0.130 | 27 | 5 |
| hybrid | 0.93 | 0.67 | 0.462 | 27 | 307 |

## Chat summary

| Metric | Value | Denominator |
|---|---|---|
| citation_document_accuracy | 0.76 | answerable questions with ≥1 citation |
| citation_page_hit_rate | 0.78 | all answerable questions |
| false_abstention_rate | 0.07 | all answerable questions (lower is better) |
| abstention_rate | 1.00 | unanswerable questions (higher is better) |
| mean latency (ms) | 12371 | evaluated chat turns |
| total tokens | 160,484 | final chat turns (setup turns not counted) |
| estimated cost | $0.1079 | model gpt-5-mini-2025-08-07 @ static rates |

## Per-category breakdown

| category | n | vec_page | key_page | hyb_page | chat_cite_doc | chat_cite_page | abstain* |
|---|---|---|---|---|---|---|---|
| keyword | 8 | 0.88 | 0.38 | 0.75 | 1.00 | 0.88 | 0.00 |
| semantic | 8 | 0.62 | 0.12 | 0.62 | 0.50 | 0.88 | 0.00 |
| cross-document | 5 | 0.60 | 0.00 | 0.60 | 0.60 | 0.60 | 0.00 |
| hard | 4 | 1.00 | 0.25 | 1.00 | 1.00 | 1.00 | 0.00 |
| unanswerable | 3 | — | — | — | — | — | 1.00 |
| multi-turn | 2 | 0.00 | 0.00 | 0.00 | — | 0.00 | 1.00 |

Retrieval columns are page_hit rates (blank where an engine did not run, e.g. unanswerables). `abstain*` is the false-abstention rate for answerable categories and the correct-abstention rate for `unanswerable`.

## Failure evidence: citation-document misses

Cross-document entries score **pages against the primary (first-listed) document only**; secondary documents are credited at the document level. The citation-document metric is a **strict subset check** (all cited docs must be expected), so it can penalize a legitimate secondary or neighbouring citation even when the answer is well grounded — see the failure-evidence section to adjudicate each case. (Dataset note: `cross_document_scoring`.)

| id | expected | cited | unexpected (cited∉expected) |
|---|---|---|---|
| mg-03-cross-document-incident-notification | nis2.pdf | nis1.pdf, nis2.pdf | nis1.pdf |
| dg-s01-semantic-gdpr-portability | gdpr.pdf | data-act.pdf | data-act.pdf |
| dg-s03-semantic-eprivacy-cookies | eprivacy-directive.pdf | data-act.pdf, eprivacy-directive.pdf | data-act.pdf |
| dg-s04-semantic-nis2-risk-measures | nis2.pdf | cra.pdf, nis2.pdf | cra.pdf |
| dg-s07-semantic-dsm-text-and-data-mining | dsm-copyright-directive.pdf | ai-act.pdf, dsm-copyright-directive.pdf | ai-act.pdf |
| dg-x01-cross-international-transfers | gdpr.pdf, scc-decision.pdf | adequacy-south-korea.pdf, adequacy-us-dpf.pdf, digital-content-directive.pdf, gdpr.pdf | adequacy-south-korea.pdf, adequacy-us-dpf.pdf, digital-content-directive.pdf |

## Per-question detail

| id | category | vec | key | hyb | chat_cites | flags |
|---|---|---|---|---|---|---|
| mg-01-keyword-dpo | keyword | ✓✓ | ✓✓ | ✓✓ | 1 | — |
| mg-02-semantic-digital-content-remedies | semantic | ✓✓ | ·· | ✓✓ | 3 | — |
| mg-03-cross-document-incident-notification | cross-document | ✓✓ | ·· | ✓✓ | 4 | cite-doc✗ |
| mg-04-hard-mica-art-white-paper | hard | ✓✓ | ·· | ✓✓ | 6 | — |
| mg-05-unanswerable-water-intake | unanswerable | — | — | — | 0 | — |
| mg-06-hard-aiact-high-risk | hard | ✓✓ | ✓· | ✓✓ | 5 | — |
| dg-k01-keyword-gdpr-erasure | keyword | ✓✓ | ·· | ✓✓ | 4 | — |
| dg-k02-keyword-cra-ce-marking | keyword | ✓✓ | ✓✓ | ✓✓ | 7 | — |
| dg-k03-keyword-dsa-statement-of-reasons | keyword | ✓✓ | ✓· | ✓✓ | 3 | — |
| dg-k04-keyword-dma-gatekeeper | keyword | ✓✓ | ✓· | ✓· | 6 | — |
| dg-k05-keyword-eidas-wallet | keyword | ✓· | ✓· | ✓· | 8 | — |
| dg-k06-keyword-cybersecurity-certificate | keyword | ✓✓ | ✓✓ | ✓✓ | 3 | — |
| dg-k07-keyword-p2b-suspension | keyword | ✓✓ | ✓· | ✓✓ | 3 | — |
| dg-s01-semantic-gdpr-portability | semantic | ·· | ·· | ·· | 6 | cite-doc✗ |
| dg-s02-semantic-data-act-switching | semantic | ✓✓ | ·· | ✓✓ | 7 | — |
| dg-s03-semantic-eprivacy-cookies | semantic | ✓✓ | ·· | ✓✓ | 3 | cite-doc✗ |
| dg-s04-semantic-nis2-risk-measures | semantic | ✓✓ | ·· | ✓✓ | 6 | cite-doc✗ |
| dg-s05-semantic-accessibility-ecommerce | semantic | ✓· | ·· | ✓· | 6 | — |
| dg-s06-semantic-avmsd-minors | semantic | ✓✓ | ✓✓ | ✓✓ | 4 | — |
| dg-s07-semantic-dsm-text-and-data-mining | semantic | ✓· | ·· | ✓· | 7 | cite-doc✗ |
| dg-x01-cross-international-transfers | cross-document | ✓✓ | ·· | ✓✓ | 5 | cite-doc✗ |
| dg-x02-cross-cybersecurity-certification | cross-document | ✓· | ✓· | ✓· | 6 | — |
| dg-x03-cross-data-switching-porting | cross-document | ✓✓ | ·· | ✓✓ | 8 | — |
| dg-x04-cross-public-data-reuse | cross-document | ✓· | ✓· | ✓· | 7 | — |
| dg-h01-hard-dora-tlpt | hard | ✓✓ | ·· | ✓✓ | 1 | — |
| dg-h02-hard-dsa-crisis-mechanism | hard | ✓✓ | ✓✓ | ✓✓ | 6 | — |
| dg-u01-unanswerable-hipaa | unanswerable | — | — | — | 0 | — |
| dg-u02-unanswerable-ccpa | unanswerable | — | — | — | 0 | — |
| dg-m01-multiturn-dpo-appointment | multi-turn | ✓· | ·· | ✓· | 0 | false-abstain |
| dg-m02-multiturn-highrisk-provider-obligations | multi-turn | ✓· | ✓· | ·· | 0 | false-abstain |

Retrieval marks are `doc``page` (`✓`/`·`); `—` where the engine did not run.

## Denominators & notes

- **Retrieval n = 27** (of 30): the 3 unanswerable questions skip retrieval (no expected document), so retrieval is scored over the answerable set only.
- **Multi-turn** questions count as answerable and DO run retrieval, but on the final question text alone — the retrieval endpoints are stateless (no conversation history), which can understate multi-turn retrieval. Their chat turn replays the setup turns on one conversation before the evaluated question.
- **Chat evaluated = 30** (all questions): `citation_document_accuracy` is over answerable questions with ≥1 citation; `citation_page_hit_rate` and `false_abstention_rate` are over all answerable questions; `abstention_rate` is over unanswerable questions.

- _Cross-document scoring: page metrics are against the primary (first-listed) expected document only; secondary documents are credited at the document level._
- _Abstention is a deterministic proxy: a chat turn counts as abstained iff it returned zero citations (the answer text is not inspected)._
