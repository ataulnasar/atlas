# Atlas evaluation comparison

Baseline `fixture-golden` → candidate `fixture-golden` (tolerance ±0.02). Gating verdict: **REGRESSED**.

## Engine: vector

| metric | baseline | candidate | Δ |
|---|---|---|---|
| doc_hit | 1.000 | 0.667 | **⚠ −0.333 ↓** |
| page_hit | 1.000 | 0.667 | **⚠ −0.333 ↓** |
| mrr | 1.000 | 0.667 | **⚠ −0.333 ↓** |
| mean_ms | 0 | 0 | +0 |

## Engine: keyword

| metric | baseline | candidate | Δ |
|---|---|---|---|
| doc_hit | 0.333 | 0.333 | +0.000 |
| page_hit | 0.333 | 0.333 | +0.000 |
| mrr | 0.333 | 0.333 | +0.000 |
| mean_ms | 0 | 0 | +0 |

## Engine: hybrid

| metric | baseline | candidate | Δ |
|---|---|---|---|
| doc_hit | 1.000 | 0.667 | **⚠ −0.333 ↓** |
| page_hit | 1.000 | 0.667 | **⚠ −0.333 ↓** |
| mrr | 1.000 | 0.667 | **⚠ −0.333 ↓** |
| mean_ms | 0 | 0 | +0 |

## Chat

| metric | baseline | candidate | Δ |
|---|---|---|---|
| citation_document_accuracy | 0.667 | 0.667 | +0.000 |
| citation_page_hit_rate | 1.000 | 1.000 | +0.000 |
| false_abstention_rate | 0.000 | 0.000 | +0.000 |
| abstention_rate | 1.000 | 1.000 | +0.000 |
| mean_latency_ms | 0 | 0 | +0 |

## Per-question regressions

- s1 · vector: document hit → miss
- s1 · vector: page hit → miss
- s1 · hybrid: document hit → miss
- s1 · hybrid: page hit → miss

Exit contract: 0 when clean; 1 when any gated aggregate metric regressed beyond ±0.02. Latency is shown but not gated.
