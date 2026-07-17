# Demo corpus: EU digital regulation

A ~30-document demo corpus of EU digital-regulation legal texts, used to exercise Atlas
ingestion with real, page-heavy legal PDFs (Phase 1), and later as the source material for
Phase 2 retrieval tuning and the Phase 4 golden evaluation dataset.

## What's in it

`manifest.json` lists every document: `id`, official `title`, a `short_title` (e.g. `GDPR`),
the EUR-Lex `celex` number, the `source_url` (the EUR-Lex English PDF), a `category`
(`regulation`, `directive`, or `guideline`), and an `approx_pages` count.

The corpus covers the core EU digital rulebook — GDPR, the AI Act, DSA, DMA, Data Act, Data
Governance Act, NIS2, Cyber Resilience Act, eIDAS2, the P2B Regulation, the ePrivacy
Directive, the Open Data Directive, the DSM Copyright Directive, the European Accessibility
Act — plus adjacent regulations (Cybersecurity Act, MiCA, DORA, the EECC, and others) and a
`guideline` category filled with genuine EUR-Lex-published Commission Implementing Decisions
and Recommendations (GDPR adequacy decisions for the UK/Japan/South Korea and the EU-US Data
Privacy Framework, the Standard Contractual Clauses decision, and recommendations on illegal
content online and 5G cybersecurity).

**Note on "guideline" documents:** genuine EDPB Guidelines (the European Data Protection
Board's own soft-law guidance) are published on edpb.europa.eu, not EUR-Lex, and don't carry
CELEX numbers — they don't fit this manifest's uniform "CELEX + EUR-Lex PDF" schema. To keep
every entry verifiable the same way (a CELEX number that resolves to a EUR-Lex PDF), the
`guideline` slots are filled with real Commission Decisions and Recommendations instead. If a
future card wants actual EDPB Guidelines, they'll need a manifest schema that tolerates a
missing CELEX and a non-EUR-Lex source URL.

Every `source_url` in the manifest was checked with a live HTTP request (status 200,
`Content-Type: application/pdf`) before being added, and `approx_pages` was computed by
counting `/Type /Page` objects in the actual downloaded PDF — not guessed.

## Licensing

EUR-Lex content is reusable under the EU's official document reuse policy (Commission
Decision 2011/833/EU of 12 December 2011): reproduction is authorised provided the source is
acknowledged, unless otherwise stated. See the
[EUR-Lex legal notice](https://eur-lex.europa.eu/content/legal-notice/legal-notice.html) for
the full terms. None of this content is copied into the repository — `manifest.json` only
records where to fetch it from; the PDFs themselves are downloaded on demand into
`corpus/files/`, which is gitignored.

## Usage

```bash
# 1. Download every manifest document into corpus/files/ (idempotent — safe to re-run)
./corpus/download.sh

# 2. Ingest every downloaded file into a running Atlas instance
ATLAS_BASE_URL=http://localhost:8080 ./corpus/ingest.sh

# 3. (repeat step 2 against a different instance, or re-run after a code change —
#    already-ingested documents are detected via the 409 duplicate-content response
#    and their existing status is reported rather than re-uploaded)
```

`download.sh` and `ingest.sh` both require `curl` and `jq` on `PATH`. `ingest.sh` reads
`ATLAS_BASE_URL` (default `http://localhost:8080`) and an optional `ATLAS_API_KEY`, sent as
the `X-API-Key` header — Atlas doesn't enforce API key auth yet (see docs/plan.md Phase 3),
but the script is ready for when it does.
