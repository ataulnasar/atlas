# Demo vertical: EU digital-regulation knowledge assistant

This is Atlas's **reference deployment** — the concrete, measured example an FDE points a
prospect at, and the template an FDE clones to stand up a new customer vertical. Everything
below is real: a real corpus, a real evaluated baseline, and a demo you can run end to end.

## What it is

A retrieval-augmented question-answering assistant over the **EU digital rulebook** — GDPR, the
AI Act, the DSA, the DMA, the Data Act, NIS2, the Cyber Resilience Act, eIDAS2, MiCA, DORA, and
~20 more (see the corpus below). You ask a natural-language question ("When must an operator of
an essential service notify a significant incident?"); Atlas retrieves the relevant passages,
generates an answer **grounded in those passages**, and returns **inline citations that point to
the exact source document and page**. If the answer isn't in the corpus, it says so instead of
guessing.

It exercises the whole Atlas pipeline on genuinely hard material — long, dense, cross-referencing
legal PDFs where the right answer often lives on one page out of eight hundred, and where a
confident-but-wrong answer is worse than no answer.

## Who it's for

Compliance, legal, and policy teams who need to **find and cite the governing text fast** and
who cannot act on an unsourced answer:

- **Compliance officers** mapping a product or process to obligations across overlapping
  regulations.
- **In-house counsel and DPOs** who need the specific article and page behind an answer before
  they'll rely on it.
- **Policy and public-affairs teams** tracking how a requirement is phrased across instruments.

The through-line: this audience trusts a tool only if every claim is **traceable to a page** and
the tool **abstains** rather than inventing when the corpus is silent. That's exactly what Atlas
is built and measured to do.

## The corpus

~30 EU digital-regulation legal texts, sourced from EUR-Lex (each `source_url` verified as a live
`application/pdf`, page counts computed from the actual PDFs — not guessed). The PDFs are fetched
on demand and never committed; the manifest records where to get them.

See [`corpus/README.md`](../corpus/README.md) for the full list, the manifest schema, the
licensing basis (EU document-reuse policy), and the `download.sh` / ingest recipe.

## The measured baseline

Atlas ships with a committed evaluation of this exact vertical, run by `atlas-evals` against a
30-question golden dataset (`atlas-demo-golden`, `top_k=5`). Full report, per-category breakdown,
and failure evidence: [`docs/eval-report-baseline.md`](../docs/eval-report-baseline.md).

Headline numbers:

| Layer | Metric | Result |
|---|---|---|
| Retrieval (vector) | doc_hit / page_hit / MRR | **0.96 / 0.70 / 0.53** |
| Retrieval (hybrid) | doc_hit / page_hit / MRR | 0.93 / 0.67 / 0.46 |
| Retrieval (keyword only) | doc_hit / page_hit / MRR | 0.48 / 0.19 / 0.13 |
| Chat | citation-document accuracy | **0.76** |
| Chat | citation-page hit rate | **0.78** |
| Chat | false-abstention rate (answerable) | 0.07 (lower is better) |
| Chat | abstention rate (unanswerable) | **1.00** (never hallucinated on out-of-corpus questions) |
| Chat | mean latency / cost | ~12 s per answer / **~$0.11** for all 30 questions |

Two findings worth saying out loud in a demo, because they're the argument for the architecture:

- **Semantic retrieval is not optional here.** Keyword-only retrieval collapses to 0.19 page_hit —
  legal language rarely uses the searcher's words. Vector/hybrid retrieval is what makes this
  corpus answerable at all.
- **Abstention is reliable.** On every out-of-corpus question (e.g. "recommended daily water
  intake") the assistant declined to answer. For a compliance audience, *not* fabricating is a
  feature, and it's measured, not asserted.

The honest caveats are in the report too (the citation-document metric is a strict subset check
that penalizes some legitimate secondary citations; multi-turn is the weakest category) — carrying
the failure evidence is part of the pitch, not a thing to hide.

## Demo script outline

A ~5-minute walkthrough that lands the four things this audience cares about, in order:

1. **Cited answer.** Ask a keyword-style question with a clean answer — *"Who must a controller
   designate as a data protection officer?"* Show the grounded answer with inline citations.
2. **Citation drill-down.** Click a citation. Land on the **exact page** of the source PDF and
   confirm the answer matches the text. This is the trust moment: the answer is checkable.
3. **Abstention.** Ask something outside the corpus — *"What's the recommended daily water
   intake?"* The assistant **declines** rather than inventing a regulation. Contrast with a
   generic chatbot.
4. **Multi-turn.** Ask a follow-up that only makes sense in context — *"And what's the deadline
   for that notification?"* after an incident-reporting question — to show the conversation
   carries state into retrieval.

(Optional closer: run `atlas-eval run --dataset mini-golden` live against the stack to show the
quality bar is a command, not a slide.)

## Adapting this to a customer corpus

This vertical is a **template, not a special case**. Atlas core is corpus-agnostic; standing up a
new vertical is three swaps and no code change:

1. **Swap the corpus.** Replace the EU-regulation PDFs with the customer's documents (contracts,
   policies, technical standards, support KB — whatever the assistant should know) and ingest
   them. See the ingestion playbook in [`playbooks/`](playbooks/).
2. **Configure strip-patterns.** Point `ATLAS_PARSING_STRIP_LINE_PATTERNS` at the customer's page
   furniture — repeating headers/footers, Bates stamps, confidentiality banners — so that noise
   never reaches the chunker or a citation. (In this vertical the patterns match EUR-Lex footers.)
3. **Author a golden dataset.** Have the customer's SMEs supply representative questions with the
   expected source documents and pages, then run `atlas-evals` to establish **the customer's own
   baseline before go-live**. That baseline is the sign-off gate and the regression guard — same
   `run` / `report` / `compare` loop this vertical uses.

Swap the corpus + strip-patterns + golden dataset, and you have a new measured vertical. Nothing
in `atlas-core` changes.
