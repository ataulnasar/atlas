# Retrieval quality: why hybrid search

This document shows how Atlas's three retrieval engines — pure **vector** (pgvector cosine),
pure **keyword** (Postgres full-text with `ts_rank_cd`), and **hybrid** (the two fused with
Reciprocal Rank Fusion) — actually behave on the demo corpus, and why the hybrid default earns its
place. Every claim below is paired with the command that produced it, run against a local stack
with the 29-document EU-regulation corpus ingested and embedded (see
[`corpus/`](../corpus/README.md)).

All examples assume the stack is up and reachable at `http://localhost:8080`:

```bash
docker compose -f docker/docker-compose.yml up -d   # requires your own docker/.env
```

The endpoints share one request shape — `{"query": ..., "topK": ...}` — and one hit shape. A
single hybrid hit on the wire looks like this (snippet truncated here for display):

```json
{
  "citationId": "c1",
  "chunkId": "1673e355-c729-4ec8-87b5-4f4b340b9948",
  "documentId": "419ba13d-1681-4640-9fff-e8148e70832a",
  "documentFilename": "ai-act.pdf",
  "documentTitle": "ai-act.pdf",
  "startPage": 14,
  "endPage": 14,
  "snippet": "falling within the scope of certain Union harmonisation legislation listed in a …",
  "chunkIndex": 38,
  "score": 0.029911,
  "foundBy": "both",
  "vectorRank": 4,
  "keywordRank": 10
}
```

`foundBy` / `vectorRank` / `keywordRank` are hybrid-only fields (vector and keyword hits omit
them). They exist precisely to make the fusion mechanism visible, and they carry the argument of
the first example.

---

## 1. The hard case: one engine's confident wrong answer, corrected by fusion

**Query:** *"What does the AI Act consider a high-risk AI system?"* (mini-golden `mg-06`)

The correct source is the AI Act's **Article 6** classification rules for high-risk systems —
which the corpus chunks around pages 14 (the classifying recital) and 54–56 (the Article itself:
indexes 38, 139, 141, 142). Here is what each engine returns.

### Pure vector

```bash
curl -s -X POST http://localhost:8080/api/search/vector \
  -H 'Content-Type: application/json' \
  -d '{"query":"What does the AI Act consider a high-risk AI system?","topK":5}' \
  | jq '.results[] | {documentFilename, chunkIndex, startPage, endPage, score}'
```

```json
{"documentFilename":"ai-act.pdf","chunkIndex":138,"startPage":53,"endPage":54,"score":0.6848}
{"documentFilename":"ai-act.pdf","chunkIndex":275,"startPage":126,"endPage":127,"score":0.6777}
{"documentFilename":"ai-act.pdf","chunkIndex":64,"startPage":23,"endPage":24,"score":0.6709}
{"documentFilename":"ai-act.pdf","chunkIndex":38,"startPage":14,"endPage":14,"score":0.6656}
{"documentFilename":"ai-act.pdf","chunkIndex":153,"startPage":61,"endPage":61,"score":0.66}
```

Vector's **#1 is chunk 138** — and it is the wrong chunk. Chunk 138 (pp. 53–54) is about
publishing *annual reports on the use of real-time remote biometric identification systems*:

> "… 7. The Commission shall publish annual reports on the use of real-time remote biometric
> identification systems in publicly accessible spaces …"

It is dense in exactly the vocabulary of the query — "AI system", "risk", "the Commission" — so it
scores a high cosine similarity (0.6848) without being about the high-risk *classification test* at
all. The genuinely definitional chunk 38 is present but only at rank 4.

### Pure keyword

```bash
curl -s -X POST http://localhost:8080/api/search/keyword \
  -H 'Content-Type: application/json' \
  -d '{"query":"What does the AI Act consider a high-risk AI system?","topK":5}' \
  | jq '.results[] | {documentFilename, chunkIndex, startPage, endPage, score}'
```

```json
{"documentFilename":"ai-act.pdf","chunkIndex":63,"startPage":23,"endPage":23,"score":0.0056}
{"documentFilename":"ai-act.pdf","chunkIndex":114,"startPage":42,"endPage":43,"score":0.0045}
{"documentFilename":"ai-act.pdf","chunkIndex":184,"startPage":77,"endPage":78,"score":0.0039}
{"documentFilename":"ai-act.pdf","chunkIndex":183,"startPage":76,"endPage":77,"score":0.003}
{"documentFilename":"ai-act.pdf","chunkIndex":161,"startPage":65,"endPage":66,"score":0.0025}
```

Keyword finds neither chunk 138 nor the Article 6 chunks in its top 5. `websearch_to_tsquery`
AND-s the content words of a full question, so a chunk must contain *all* of "consider", "high",
"risk", "AI", "system" to match — scattering the results across incidental co-occurrences. Crucially
for the next step, **chunk 138 is not in keyword's candidate pool at all**.

### Hybrid (RRF)

```bash
curl -s -X POST http://localhost:8080/api/search/hybrid \
  -H 'Content-Type: application/json' \
  -d '{"query":"What does the AI Act consider a high-risk AI system?","topK":5}' \
  | jq '.results[] | {documentFilename, chunkIndex, startPage, endPage, score, foundBy, vectorRank, keywordRank}'
```

```json
{"documentFilename":"ai-act.pdf","chunkIndex":38,"startPage":14,"endPage":14,"score":0.029911,"foundBy":"both","vectorRank":4,"keywordRank":10}
{"documentFilename":"ai-act.pdf","chunkIndex":142,"startPage":55,"endPage":56,"score":0.029631,"foundBy":"both","vectorRank":8,"keywordRank":7}
{"documentFilename":"ai-act.pdf","chunkIndex":139,"startPage":54,"endPage":54,"score":0.028778,"foundBy":"both","vectorRank":10,"keywordRank":9}
{"documentFilename":"ai-act.pdf","chunkIndex":141,"startPage":54,"endPage":55,"score":0.026709,"foundBy":"both","vectorRank":18,"keywordRank":12}
{"documentFilename":"ai-act.pdf","chunkIndex":226,"startPage":100,"endPage":100,"score":0.02486,"foundBy":"both","vectorRank":43,"keywordRank":6}
```

Hybrid's top 4 are the Article 6 chunks — 38, 142, 139, 141 — and chunk 138 is **gone from the top
5**.

The `foundBy` / rank fields show exactly why. RRF scores each chunk as the sum, over the lists it
appears in, of `1 / (60 + rank)`:

- **Chunk 138** appears in the vector list only (`foundBy` would read `"vector"`, `keywordRank`
  null). Its fused score is a single vote: `1 / (60 + 1) = 0.016393`. Being #1 in one engine buys
  it one term.
- **Chunk 38** appears in *both* lists (`foundBy: "both"`, `vectorRank: 4`, `keywordRank: 10`), so
  it collects two votes: `1/(60+4) + 1/(60+10) = 0.029911` — nearly double chunk 138's score,
  despite never being #1 anywhere.

You can watch chunk 138 lose. Ask for a deep result set and find it:

```bash
curl -s -X POST http://localhost:8080/api/search/hybrid \
  -H 'Content-Type: application/json' \
  -d '{"query":"What does the AI Act consider a high-risk AI system?","topK":50}' \
  | jq '.results[] | select(.chunkIndex==138) | {chunkIndex, foundBy, vectorRank, keywordRank, score}'
```

```json
{"chunkIndex":138,"foundBy":"vector","vectorRank":1,"keywordRank":null,"score":0.016393}
```

The definitional chunks are middling in *each* engine (ranks 4–18 in vector, 6–12 in keyword) but
**corroborated by both**; the off-topic chunk is #1 in one engine but **stands alone**. Fusion
rewards agreement over any single engine's confidence — and here that is exactly what pulls the
right answer to the top.

---

## 2. Complementarity: where keyword misses entirely and vector carries it

**Query:** *"If software or a streaming service I paid for does not work as advertised, what can I
demand from the trader?"* (mini-golden `mg-02`)

The answer is in the **Digital Content Directive** (remedies for non-conforming digital content,
Art. 14 / pp. 22–25). The question is a lay paraphrase: it shares almost no rare terms with the
statutory language ("conformity", "remedies", "trader" aside).

### Pure vector — all five hits are the right document

```bash
curl -s -X POST http://localhost:8080/api/search/vector \
  -H 'Content-Type: application/json' \
  -d '{"query":"If software or a streaming service I paid for does not work as advertised, what can I demand from the trader?","topK":5}' \
  | jq -r '.results[] | "\(.documentFilename)  idx=\(.chunkIndex)  p\(.startPage)-\(.endPage)  score=\(.score)"'
```

```
digital-content-directive.pdf  idx=52  p22-23  score=0.4972
digital-content-directive.pdf  idx=53  p23-23  score=0.4951
digital-content-directive.pdf  idx=31  p13-13  score=0.4948
digital-content-directive.pdf  idx=56  p24-25  score=0.4837
digital-content-directive.pdf  idx=29  p12-13  score=0.4764
```

### Pure keyword — the right document is nowhere in the top 5

```bash
curl -s -X POST http://localhost:8080/api/search/keyword \
  -H 'Content-Type: application/json' \
  -d '{"query":"If software or a streaming service I paid for does not work as advertised, what can I demand from the trader?","topK":5}' \
  | jq -r '.results[] | "\(.documentFilename)  idx=\(.chunkIndex)  p\(.startPage)-\(.endPage)  score=\(.score)"'
```

```
dma.pdf  idx=75  p35-35  score=1.4
dma.pdf  idx=27  p12-13  score=1.3
cra.pdf  idx=9   p4-5    score=1.1
cra.pdf  idx=109 p45-46  score=1.0
cra.pdf  idx=10  p5-5    score=0.9
```

Keyword latches onto incidental matches for "service", "market", "work" in the DMA and Cyber
Resilience Act and never surfaces the Digital Content Directive. On this question, embeddings are
the only thing that works — the mirror image of Example 1, where keyword's precision was what
demoted a vector false positive. Neither engine alone is sufficient; hybrid exists to hold both.

(Honest note: hybrid ranks the correct chunk **2nd** here, not 1st — a coincidentally
double-matched DMA chunk edges it out at the top. See `mg-02` in the baseline below: vector `r1`,
hybrid `r2`. Fusion is a robust default, not a guarantee of the single best rank on every query.)

---

## 3. Measured baseline: the mini golden dataset

The current measured state, all six golden questions across all three engines, produced by the
committed spot-check script against the live stack:

```bash
./atlas-evals/mini-golden-check.sh
```

```
ID                                           CATEGORY       EXPECTED                       VECTOR       KEYWORD      HYBRID
mg-01-keyword-dpo                            keyword        gdpr.pdf                       hit r1       hit r1       hit r1
mg-02-semantic-digital-content-remedies      semantic       digital-content-directive.pdf  hit r1       MISS         hit r2
mg-03-cross-document-incident-notification   cross-document nis2.pdf                       hit r1       MISS         hit r1
mg-04-hard-mica-art-white-paper              hard           mica.pdf                       hit r1       MISS         hit r1
mg-05-unanswerable-water-intake              unanswerable   (none)                         top=0.201    top=none     top=0.016393
mg-06-hard-aiact-high-risk                   hard           ai-act.pdf                     hit r1       hit r1       hit r1
```

`hit rN` = the expected document appeared at rank N of the top 5; `MISS` = not in the top 5;
`top=S` = top-result score for the unanswerable question (lower is better).

The questions, expected documents/pages, and rationale live in
[`atlas-evals/datasets/mini-golden.json`](../atlas-evals/datasets/mini-golden.json). What this
table shows at a glance:

- **Vector** hits the expected document at rank 1 on all five answerable questions — the strongest
  single engine on this corpus.
- **Keyword** misses three of the five answerable questions outright: it wins on short term-dense
  queries (`mg-01`, `mg-06`) but its AND-semantics fail full natural-language questions.
- **Hybrid** matches vector's document-level hits and, as Example 1 shows, wins at the finer
  *chunk* level that this table cannot see (below).
- **Unanswerable** (`mg-05`): the top vector score (0.201) is far below any answerable question's
  (≈0.5–0.8), the signal a future abstention check would threshold on.

---

## 4. Limitations

These results are honest about what they do and do not prove.

- **Document-level `hit@5` is blind to chunk-level regressions.** The baseline table scores whether
  the right *document* appears in the top 5. On `mg-06` it reads `hit r1` for all three engines —
  yet Example 1 shows vector's #1 *chunk* is the wrong one (138, biometric reporting) and only
  hybrid surfaces the Article 6 chunks. A document-level metric literally cannot see the idx-138
  problem. This is why `mg-06`'s dataset note pins the specific chunk indexes, and why the Phase 4
  golden-schema card is required to add page-level matching (top-K chunk page ranges intersecting
  `expected_pages`), not just document hit@K.
- **Keyword AND-semantics trade recall for precision.** `websearch_to_tsquery` requires every
  content word to co-occur in a chunk. That makes keyword excellent at exact-term lookups and
  near-useless on paraphrased full questions (`mg-02`, `mg-03`, `mg-04` all `MISS`). It is a
  precision instrument, not a recall instrument — which is exactly why it is fused with vector
  rather than used alone.
- **Six questions is a spot-check, not a benchmark.** The mini golden set exists to make Phase 2
  tuning measured rather than blind; it is not large enough to compare configurations with
  confidence. The full Phase 4 golden dataset is what quantitative claims should rest on.
- **This is the baseline that gates re-ranking.** Cross-encoder re-ranking is deliberately excluded
  from v1 as a *benchmark hypothesis* — it enters v2 only with a before/after `atlas-eval compare`
  showing a hit-rate/MRR lift over this hybrid baseline (see
  [ADR 0006](adr/0006-v1-exclusions.md)). The numbers here are the "before" it will have to beat.
