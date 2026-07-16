# Atlas v1 — Trello Backlog with AI Prompts (rev. 2)

**Total estimated effort:** 236 hours

**Assumption:** Strong AI assistance using Claude Code/Cursor/ChatGPT for scaffolding, tests, migrations, docs, and review.

**Cadence:** Target ~19h/week alongside Swedish course, Dealflow maintenance, and FDE applications → ~12 weeks, roughly one phase per 1.5–2 weeks. Phase 6 LinkedIn posts are float and can be deferred without blocking release.

**Rev. 2 changes:** Demo corpus moved Phase 5 → Phase 1 (dependency fix — Phase 2 tuning and Phase 4 golden dataset need it). Mini golden dataset added to Phase 2 so retrieval tuning is never blind. New cards: secrets scanning (Phase 0), structured logging + correlation IDs (Phase 0), API key auth (Phase 3). Fixed mislabeled Python client card. Clarified SSE citation event and embedding dimension/provider coupling. Reranking explicitly parked as v2 with benchmark hypothesis.

## Phase Summary

- **Phase 0 — Foundation:** 23 hours
- **Phase 1 — Ingestion:** 41 hours
- **Phase 2 — Retrieval:** 44 hours
- **Phase 3 — Chat/RAG:** 40 hours
- **Phase 4 — Python Evals:** 39 hours
- **Phase 5 — FDE Demo:** 21 hours
- **Phase 6 — Polish/Launch:** 28 hours

---


## Phase 0 — Foundation

### Create public repository structure — 2h

**Priority:** High  
**Labels:** foundation,repo

**Description:** Create atlas repo with atlas-core, atlas-evals, atlas-fde, docker, docs. Add .gitignore, license, basic folder README files.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Act as a senior Java/Spring architect. Generate a clean public GitHub repository structure for Atlas v1 with folders atlas-core, atlas-evals, atlas-fde, docker, docs. Include .gitignore, Apache 2.0 license placeholder, and short README stubs for each folder. Keep v1 scope focused on Java/Spring RAG + Python evals + FDE playbooks.
```

**Definition of Done:** Repo structure exists; folders are documented; no implementation scope beyond v1.

### Write README first — 3h

**Priority:** High  
**Labels:** foundation,docs

**Description:** Write the main README: project description, what v1 includes, what v1 excludes, quickstart placeholder, architecture overview, roadmap.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Write a professional README for Atlas v1. Position it as an open-source Java/Spring RAG platform with Python evaluation harness and FDE playbooks. Include sections: What it is, What v1 includes, What is not in v1, Architecture, Quickstart placeholder, Roadmap, License.
```

**Definition of Done:** README clearly explains scope, ambition, and strict v1 exclusions.

### Initialize Spring Boot application — 3h

**Priority:** High  
**Labels:** java,spring,foundation

**Description:** Create Java 21 Spring Boot 3.x app in atlas-core with base packages, health endpoint, validation, actuator, test setup.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Create a Java 21 Spring Boot 3.x application for Atlas Core. Use clean package structure: config, document, ingestion, retrieval, chat, common. Add Actuator health endpoint, validation dependency, basic application properties, and one smoke test.
```

**Definition of Done:** App builds and health endpoint works locally.

### Create Docker Compose skeleton — 2h

**Priority:** High  
**Labels:** docker,postgres

**Description:** Add docker compose with atlas-core service and PostgreSQL 16 + pgvector. Include env file example.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Generate docker-compose.yml for Atlas v1 with PostgreSQL 16 + pgvector and atlas-core service. Include .env.example, volumes, health checks, and sensible local development ports.
```

**Definition of Done:** docker compose up starts Postgres and app skeleton.

### Add Flyway baseline migrations — 2h

**Priority:** High  
**Labels:** database,flyway

**Description:** Configure Flyway and create initial migration for extension setup and basic schema placeholder.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Configure Flyway for Spring Boot with PostgreSQL. Create V1 migration enabling pgvector extension and basic schema setup. Add test profile using Testcontainers later.
```

**Definition of Done:** Flyway runs successfully on app startup.

### Configure GitHub Actions CI — 4h

**Priority:** High  
**Labels:** ci,java,python

**Description:** Add CI for Java build/test/format and Python lint/test skeleton.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Create GitHub Actions workflow for Atlas. Run Java build and tests for atlas-core, Spotless or Checkstyle, and Python lint/test placeholders for atlas-evals using uv, ruff, and pytest. Keep workflow fast and reliable.
```

**Definition of Done:** CI is green on main branch.

### Add secrets scanning from day one — 2h

**Priority:** High  
**Labels:** security,foundation

**Description:** Add gitleaks (pre-commit hook + CI job) before any API keys enter the workflow. This is a public repo; one leaked key is permanent in git history.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Set up gitleaks for the Atlas public repo. Add a pre-commit hook and a GitHub Actions job that fails on detected secrets. Include a .gitleaks.toml with sensible defaults and document setup in CONTRIBUTING or README.
```

**Definition of Done:** Committing a fake API key is blocked locally and fails CI.

### Add structured JSON logging with correlation IDs — 3h

**Priority:** High  
**Labels:** logging,foundation

**Description:** Configure structured JSON logging (Logback + logstash encoder) with per-request correlation IDs via MDC filter. Same pattern as ai_portfolio_analysis — carries the "production-ready" claim. Full observability stack stays in v2.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Configure structured JSON logging for Atlas Core using Logback with logstash-logback-encoder. Add a servlet filter that generates a correlation ID per request, stores it in MDC, includes it in all log lines, and returns it as a response header. Keep console output human-readable in the local profile and JSON in the default profile.
```

**Definition of Done:** Every request produces JSON logs sharing one correlation ID.

### Create architecture decision records — 2h

**Priority:** Medium  
**Labels:** docs,architecture

**Description:** Add ADRs for Java 21, Spring Boot 3.x, PostgreSQL+pgvector, Python eval harness, no Kubernetes/Redis/multi-tenancy in v1. Include an ADR acknowledging that the vector column dimension is coupled to text-embedding-3-small (1536): swapping embedding providers requires a migration + re-embedding. Documented honestly rather than overclaiming "provider-swappable."

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Write concise ADRs for Atlas v1 decisions: Java 21, Spring Boot 3.x, Spring AI, PostgreSQL 16 + pgvector, Python 3.12 eval harness, Docker Compose first, and explicit v1 exclusions. Include one ADR on embedding provider choice explaining that the pgvector column dimension (1536, text-embedding-3-small) couples the schema to the provider, and that switching providers requires migration and re-embedding.
```

**Definition of Done:** ADRs exist in docs/decisions and explain tradeoffs.


## Phase 1 — Ingestion

### Design document and chunk schema — 3h

**Priority:** High  
**Labels:** database,ingestion

**Description:** Create Flyway migrations for document and chunk tables with status, hash, metadata, page number, position fields.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Design PostgreSQL schema for Atlas ingestion. Create Flyway migration for documents and chunks. Include fields for id, filename, content hash, content type, status, error message, page number, chunk index, token/char counts, metadata JSON, created/updated timestamps.
```

**Definition of Done:** Tables exist and schema supports ingestion status tracking.

### Build upload endpoint — 4h

**Priority:** High  
**Labels:** api,ingestion

**Description:** Create POST /api/documents upload endpoint accepting PDF, DOCX, TXT and returning document id/status.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Implement a Spring Boot upload endpoint for Atlas v1. Accept multipart files for PDF, DOCX, and TXT only. Validate file type and size. Persist document metadata with PENDING status and return document id plus status DTO.
```

**Definition of Done:** Upload works for allowed types; invalid files fail cleanly.

### Implement local file storage service — 3h

**Priority:** High  
**Labels:** storage,ingestion

**Description:** Store uploaded files under local storage path with safe names and document id folder.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Implement LocalFileStorageService in Spring Boot. Store uploaded files under a configurable storage directory using document id folders. Prevent path traversal, preserve original filename in DB, and return stored file path.
```

**Definition of Done:** Files are stored safely and referenced from database.

### Create parser abstraction — 3h

**Priority:** High  
**Labels:** ingestion,architecture

**Description:** Define DocumentParser interface and parser registry for PDF, DOCX, TXT.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Design a clean parser abstraction for Atlas. Create DocumentParser interface, ParsedDocument model, ParsedPage/ParsedText model, and a registry that selects parser by content type. Keep it extensible but implement only PDF, DOCX, TXT in v1.
```

**Definition of Done:** Parser abstraction exists and has unit tests.

### Implement PDF parser — 4h

**Priority:** High  
**Labels:** pdf,ingestion

**Description:** Use PDFBox or Tika to extract page-aware text from PDFs.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Implement PDF parser for Atlas using Apache PDFBox or Tika. Extract text with page numbers where possible. Return structured parsed pages. Handle encrypted or broken PDFs gracefully with meaningful errors.
```

**Definition of Done:** PDF extraction works on sample multi-page document.

### Implement DOCX and TXT parsers — 4h

**Priority:** High  
**Labels:** docx,txt,ingestion

**Description:** Use Apache POI/Tika for DOCX and simple reader for TXT.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Implement DOCX and TXT parsers for Atlas. DOCX should extract paragraphs with best-effort structure. TXT should preserve line breaks. Return ParsedDocument objects compatible with chunking.
```

**Definition of Done:** DOCX and TXT documents parse successfully.

### Implement chunking service — 5h

**Priority:** High  
**Labels:** chunking,ingestion

**Description:** Recursive character splitter with configurable chunk size and overlap; store source page and position metadata.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Build a recursive character chunking service for Atlas. Use configurable chunk size and overlap. Prefer paragraph/sentence boundaries where possible. Preserve metadata: document id, page number, chunk index, start/end character offsets.
```

**Definition of Done:** Chunks are readable, overlap works, metadata is stored.

### Build async ingestion processor — 4h

**Priority:** High  
**Labels:** async,ingestion

**Description:** Process uploaded documents asynchronously with statuses PENDING, PROCESSING, INDEXED, FAILED.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Implement asynchronous document processing in Spring Boot. After upload, process file through parsing and chunking. Update status transitions PENDING -> PROCESSING -> INDEXED or FAILED. Store error messages when processing fails.
```

**Definition of Done:** Status transitions are visible and reliable.

### Add duplicate detection — 2h

**Priority:** Medium  
**Labels:** hashing,ingestion

**Description:** Calculate content hash and avoid duplicate ingestion.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Add duplicate detection for uploaded documents using content hash. If a file with the same hash already exists, return existing document info or mark as duplicate according to a simple v1 policy.
```

**Definition of Done:** Duplicate upload does not create duplicate chunks.

### Add ingestion integration tests — 5h

**Priority:** High  
**Labels:** tests,testcontainers

**Description:** Test upload, parsing, chunking, DB persistence, duplicate detection using Testcontainers.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Write integration tests for Atlas ingestion using Testcontainers PostgreSQL. Cover PDF upload, TXT upload, chunk persistence, duplicate detection, and failed parsing behavior. Keep tests deterministic and fast.
```

**Definition of Done:** 2–4 integration tests pass in CI.

### Collect public demo corpus — 4h

**Priority:** High  
**Labels:** demo,data

**Description:** Add ~30 public/synthetic policy/legal documents suitable for demo. **Moved from Phase 5:** the corpus is needed by Phase 2 retrieval tuning and the Phase 4 golden dataset — collecting it in Phase 5 would mean tuning and evaluating against documents that don't exist yet. It also gives ingestion real test material now.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Prepare a safe demo corpus for Atlas using public or synthetic policy/legal documents. Include filenames, categories, and metadata. Avoid copyrighted or private client material.
```

**Definition of Done:** Demo corpus is committed or documented safely, and at least a few documents ingest successfully.


## Phase 2 — Retrieval

### Configure Spring AI embeddings — 4h

**Priority:** High  
**Labels:** spring-ai,embeddings

**Description:** Configure OpenAI text-embedding-3-small as default, provider-swappable via properties.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Configure Spring AI embeddings for Atlas using OpenAI text-embedding-3-small as default. Make provider/model configurable through application properties. Add clear missing-key error handling.
```

**Definition of Done:** Embedding client works with sample text.

### Add embedding columns and vector index — 4h

**Priority:** High  
**Labels:** pgvector,database

**Description:** Create migration for vector column and HNSW index on chunks.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Create Flyway migration for pgvector support on chunks. Add embedding vector column with correct dimension for text-embedding-3-small and create an HNSW index. Include comments explaining index choice.
```

**Definition of Done:** pgvector index is created successfully.

### Build embedding job — 5h

**Priority:** High  
**Labels:** embeddings,retrieval

**Description:** Generate and store embeddings for chunks after ingestion.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Implement embedding generation for Atlas chunks. After chunking, call embedding model, store vectors in pgvector, track embedding status, and handle partial failures safely.
```

**Definition of Done:** Uploaded document chunks receive embeddings.

### Implement vector search endpoint — 5h

**Priority:** High  
**Labels:** vector-search,api

**Description:** Create /api/search/vector returning topK chunks with scores and citations.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Implement vector similarity search in Spring Boot using PostgreSQL pgvector. Expose endpoint that accepts query and topK, embeds query, returns ranked chunks with score and citation metadata.
```

**Definition of Done:** Vector search returns relevant cited chunks.

### Implement keyword full-text search — 4h

**Priority:** High  
**Labels:** postgres,fts

**Description:** Add tsvector column/index and keyword search endpoint/service.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Implement PostgreSQL full-text search for chunks. Add tsvector migration, GIN index, and service method returning ranked keyword results with citation metadata.
```

**Definition of Done:** Keyword search works for exact/business terms.

### Implement hybrid search with RRF — 6h

**Priority:** High  
**Labels:** hybrid-search,rrf

**Description:** Combine vector and keyword results using Reciprocal Rank Fusion.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Implement hybrid retrieval using Reciprocal Rank Fusion. Combine vector search and keyword search rankings. Return merged ranked results with vector score, keyword rank, final RRF score, and citation metadata.
```

**Definition of Done:** Hybrid endpoint demonstrates better results than pure vector for selected examples.

### Create mini golden dataset for retrieval tuning — 2h

**Priority:** High  
**Labels:** evals,retrieval

**Description:** Write ~5 question/expected-source pairs against the demo corpus (simple JSON, no tooling yet). Use them manually while tuning chunk size, topK, and RRF so Phase 2 decisions are measured, not guessed. These questions seed the full Phase 4 golden dataset. Lesson from Parlia: you only improved 0.544 → 0.851 because an eval set existed to iterate against.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Create a mini golden dataset of 5 questions for the Atlas demo corpus as a simple JSON file: question, expected source document, expected page/section. Include one keyword-heavy question, one semantic paraphrase question, and one question the corpus cannot answer. Document how to use it manually to compare vector vs keyword vs hybrid retrieval while tuning.
```

**Definition of Done:** 5 questions committed; retrieval tuning decisions reference their results.

### Add metadata filtering — 3h

**Priority:** Medium  
**Labels:** retrieval,filters

**Description:** Filter by document type, date, tags/source metadata.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Add metadata filtering to Atlas retrieval. Support document type and simple metadata JSON filters. Keep API small and easy to extend.
```

**Definition of Done:** Search can filter results by basic metadata.

### Create citation response model — 3h

**Priority:** High  
**Labels:** citations,api

**Description:** Standardize citation DTO with document id/title/page/chunk/score.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Design a Citation DTO for Atlas retrieval and RAG responses. Include document id, filename/title, page number, chunk id, chunk index, score, and source excerpt. Use it consistently in search and chat.
```

**Definition of Done:** All retrieval results include standardized citations.

### Retrieval quality examples — 3h

**Priority:** Medium  
**Labels:** demo,docs

**Description:** Create 5 example queries showing vector, keyword, and hybrid behavior.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Using the existing demo corpus (ingested in Phase 1), create 5 example queries that show why hybrid search is useful — reuse or extend the mini golden dataset. Document expected behavior and save screenshots or JSON outputs for README/LinkedIn.
```

**Definition of Done:** Examples are committed under docs/examples.

### Retrieval tests — 5h

**Priority:** High  
**Labels:** tests,retrieval

**Description:** Test vector search, keyword search, RRF merge behavior, citation shape.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Write unit and integration tests for Atlas retrieval. Cover RRF ranking, citation DTO mapping, keyword search, vector search with mocked embeddings where needed, and API response shape.
```

**Definition of Done:** Retrieval tests pass reliably.


## Phase 3 — Chat/RAG

### Design chat schema — 3h

**Priority:** High  
**Labels:** database,chat

**Description:** Create conversation and message tables with cost/token fields.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Design Flyway migration for Atlas chat. Create conversation and message tables. Include role, content, citations JSON, token counts, model, cost estimate, timestamps.
```

**Definition of Done:** Chat persistence schema exists.

### Implement streaming chat endpoint — 6h

**Priority:** High  
**Labels:** sse,chat

**Description:** Create /api/chat using SSE streaming. Event contract: token/delta events during generation, then a final `citations` event carrying the citation DTOs, then a `done` event with usage metadata. Defining this now unblocks the React work.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Implement Spring Boot SSE streaming endpoint for Atlas chat. Accept question and conversation id. Stream generated answer as token/delta events, emit a final 'citations' event with citation DTOs after generation completes, then a 'done' event with token usage. Include clean error events.
```

**Definition of Done:** Browser/client receives streamed response.

### Build RAG context assembler — 5h

**Priority:** High  
**Labels:** rag,retrieval

**Description:** Query retrieval, assemble context with numbered sources, pass to LLM.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Implement RAG context assembly for Atlas. Retrieve top chunks using hybrid search, format them as numbered sources, enforce context length limits, and pass context to generation service.
```

**Definition of Done:** Context includes cited chunks and respects limits.

### Create prompt template for cited answers — 4h

**Priority:** High  
**Labels:** prompting,rag

**Description:** Use context-only answering, inline [N] markers, I don't know behavior.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Write and implement a RAG prompt template for Atlas. Require context-only answers, inline citation markers like [1], and explicit 'I don't know based on the provided documents' behavior when context is insufficient.
```

**Definition of Done:** Answers include citation markers and avoid unsupported claims.

### Persist conversation history — 3h

**Priority:** Medium  
**Labels:** chat,database

**Description:** Save user and assistant messages, citations, model, timestamps.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Implement conversation history persistence for Atlas chat. Save user messages, assistant messages, citations JSON, model name, token usage, and timestamps. Add endpoint to fetch conversation history.
```

**Definition of Done:** Conversation history can be reloaded.

### Add token and cost logging — 3h

**Priority:** Medium  
**Labels:** cost,ai

**Description:** Store token usage and estimated cost per chat request.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Add token and cost logging for Atlas chat. Store prompt tokens, completion tokens, total tokens, model name, and estimated cost per request. Use a simple pricing config table or properties file.
```

**Definition of Done:** Each request records usage/cost metadata.

### Create React chat page — 7h

**Priority:** High  
**Labels:** frontend,react

**Description:** Build one clean Vite + Tailwind chat UI with question input and streaming answer.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Build a minimal React 18 + Vite + Tailwind chat page for Atlas. Include document upload status area, question input, streaming answer display, loading states, and error handling. Keep it one page only.
```

**Definition of Done:** User can ask questions from browser.

### Add citation display in UI — 4h

**Priority:** High  
**Labels:** frontend,citations

**Description:** Show citations under answer with source, page, excerpt.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Implement citation display for Atlas chat UI. Render citations as clickable cards with document name, page number, chunk excerpt, and score. Link citation markers in answer to citation cards if practical.
```

**Definition of Done:** Citations are visible and useful.

### Add API key authentication — 3h

**Priority:** High  
**Labels:** security,api

**Description:** Protect upload/search/chat endpoints with a simple static API key filter (header-based, key from env). Not OAuth2 — that stays in v2. An unauthenticated upload endpoint in a "production-ready" public repo is the first thing a senior reviewer will notice. Health endpoint stays open.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Implement simple API key authentication for Atlas using a Spring Security filter or OncePerRequestFilter. Read the key from an environment variable, require it as an X-API-Key header on all /api/** endpoints except actuator health, return 401 with a clean error body when missing/invalid, and update the React client and Python eval client to send it. Explicitly note OAuth2 is out of scope for v1.
```

**Definition of Done:** All API endpoints reject requests without a valid key; frontend and eval client still work.

### Record first demo video — 2h

**Priority:** Medium  
**Labels:** demo,launch

**Description:** Record 2–3 minute demo after chat works, before final polish.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Create a demo script for Atlas v1 showing upload, search, chat, citations, and streaming. Keep it under 3 minutes and focused on business value.
```

**Definition of Done:** Demo video exists for README/social posts.


## Phase 4 — Python Evals

### Create atlas-evals Python package — 3h

**Priority:** High  
**Labels:** python,evals

**Description:** Initialize Python 3.12 package with uv, ruff, pytest, typer. **Upskilling note:** Python is the primary FDE gap this project addresses — do this phase idiomatically, not just functionally: Pydantic models for dataset/result schemas, full type hints with mypy or pyright in CI, proper pytest fixtures. Same hours, much higher learning yield, and a genuinely reviewable Python package.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Create Python 3.12 package atlas-evals using uv. Add ruff, pytest, typer, mypy (or pyright), pydantic, project metadata, src layout, and a simple CLI entrypoint. Use full type hints and explain the idiomatic choices as you go — this package doubles as Python upskilling for a senior Java engineer.
```

**Definition of Done:** atlas-eval command runs locally.

### Define golden dataset schema — 3h

**Priority:** High  
**Labels:** evals,dataset

**Description:** YAML/JSON format: question, expected answer, expected sources.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Design golden dataset schema for Atlas evals. Include question, expected answer summary, expected source documents/pages/chunks, tags, difficulty, and notes. Provide JSON and YAML examples.
```

**Definition of Done:** Dataset schema documented with example file.

### Implement Python API client — 3h

**Priority:** High  
**Labels:** python,api

**Description:** Python client (in atlas-evals) that calls the running Java Atlas API — search and chat endpoints, including the X-API-Key header. Use httpx + Pydantic response models.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Implement a typed Python client for atlas-evals that calls the running Java Atlas API. Use httpx with Pydantic response models. Support chat and search endpoints, configurable base URL, API key header, timeout, and error handling.
```

**Definition of Done:** Eval harness can call local Java app.

### Implement atlas-eval run command — 5h

**Priority:** High  
**Labels:** python,cli

**Description:** Run dataset against Java API and save raw results.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Implement 'atlas-eval run --dataset path' using Typer. Load dataset, call Atlas API for each question, store raw answers, citations, retrieved sources, latency, and run metadata as JSON.
```

**Definition of Done:** One command executes a dataset.

### Implement deterministic retrieval metrics — 5h

**Priority:** High  
**Labels:** metrics,evals

**Description:** Compute hit-rate, MRR, expected source recall.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Implement deterministic retrieval metrics for Atlas evals: top-k hit rate, MRR, expected source recall, missing expected sources, and average latency. Do not require LLM judge for these metrics.
```

**Definition of Done:** Metrics are computed without LLM dependency.

### Add optional Ragas/DeepEval metrics — 5h

**Priority:** Medium  
**Labels:** ragas,deepeval

**Description:** Faithfulness, relevance, context precision/recall as optional report section.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Integrate optional Ragas or DeepEval metrics into atlas-evals. Make them opt-in so CI can remain deterministic. Include faithfulness, answer relevance, and context precision/recall where feasible.
```

**Definition of Done:** LLM-based evals can run manually.

### Generate markdown/HTML report — 4h

**Priority:** High  
**Labels:** reporting,evals

**Description:** Create report with scores, failures, examples, regression notes.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Implement 'atlas-eval report' that generates Markdown and optional HTML benchmark reports. Include summary scores, per-question table, failures, source hits, and example answers.
```

**Definition of Done:** Benchmark report generated from run output.

### Implement compare command — 3h

**Priority:** Medium  
**Labels:** regression,evals

**Description:** Compare two eval runs and detect score regressions.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Implement 'atlas-eval compare run1 run2' to compare metrics, detect regressions, and print a concise summary suitable for pull requests.
```

**Definition of Done:** Two runs can be compared.

### Create demo golden dataset — 5h

**Priority:** High  
**Labels:** dataset,demo

**Description:** Build ~20 question dataset for demo corpus.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Create a golden dataset of around 20 questions for the Atlas demo corpus. Include expected source documents and expected answer notes. Focus on citation-based knowledge questions.
```

**Definition of Done:** Dataset runs end-to-end and produces useful scores.

### Add CI smoke eval — 3h

**Priority:** Medium  
**Labels:** ci,evals

**Description:** Run small deterministic eval in CI without expensive LLM judge.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Add CI job for atlas-evals that runs a tiny deterministic smoke dataset against mocked or lightweight local API behavior. Avoid non-deterministic LLM judging in CI.
```

**Definition of Done:** CI validates eval package without flaky LLM checks.


## Phase 5 — FDE Demo

### Create demo vertical: policy/legal knowledge assistant — 3h

**Priority:** High  
**Labels:** fde,demo

**Description:** Define safe demo as policy/legal research support, not legal advice.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Design the Atlas v1 demo vertical as a policy/legal document knowledge assistant. Write scope, disclaimers, user workflow, target demo questions, and value proposition. Avoid legal-advice positioning.
```

**Definition of Done:** Demo vertical is clearly defined.

### Create deployment profile — 4h

**Priority:** High  
**Labels:** fde,deployment

**Description:** Create atlas-fde profile with .env.example, config, sizing notes.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Create an FDE deployment profile for the Atlas demo. Include .env.example, application config, storage path, model config, database settings, sizing notes, and setup instructions.
```

**Definition of Done:** Profile can configure local demo deployment.

### Write operational runbook — 5h

**Priority:** High  
**Labels:** fde,docs

**Description:** Install, upgrade, backup, restore, troubleshooting, rollback.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Write an operational runbook for Atlas v1 demo deployment. Include install, upgrade, backup, restore, troubleshooting, common failures, rollback, and health checks.
```

**Definition of Done:** A stranger can operate the demo using the runbook.

### Implement atlas doctor CLI — 5h

**Priority:** High  
**Labels:** cli,fde

**Description:** Small Java Picocli command checking API, DB, env vars, storage, model key.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Implement a small Java Picocli CLI command 'atlas doctor'. It should check Atlas API health, database connectivity, required environment variables, storage directory, and AI model configuration. Output clear pass/fail diagnostics.
```

**Definition of Done:** atlas doctor reports local environment health.

### Clean-machine quickstart test — 4h

**Priority:** High  
**Labels:** qa,quickstart

**Description:** Clone repo on clean machine/container and verify 15-minute quickstart.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Create and run a clean-machine quickstart test plan for Atlas. Verify clone, env setup, docker compose up, document upload, chat, eval run, and doctor command in under 15 minutes.
```

**Definition of Done:** Quickstart is validated and documented.


## Phase 6 — Polish/Launch

### Architecture diagram and README polish — 5h

**Priority:** High  
**Labels:** docs,launch

**Description:** Add architecture diagram, quickstart, screenshot, benchmark table.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Polish the Atlas README. Add architecture diagram, quickstart, screenshots/GIF placeholder, benchmark table, v1 features, v1 exclusions, roadmap, and contribution notes.
```

**Definition of Done:** README looks strong to recruiters/CTOs.

### Add real eval results to README — 3h

**Priority:** High  
**Labels:** evals,docs

**Description:** Run evals and publish table with metrics and date.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Run Atlas evals on demo corpus and write a concise README section showing real benchmark numbers, metric definitions, and interpretation. Be honest about limitations.
```

**Definition of Done:** README contains real eval metrics.

### Create public roadmap board/issues — 4h

**Priority:** Medium  
**Labels:** github,roadmap

**Description:** Create GitHub milestones/issues for v2 items: agents, connectors, workflows, multi-tenancy, and **cross-encoder reranking** (Cohere or similar) — written as a benchmark hypothesis ("expected +X on hit rate/MRR vs hybrid-only, measured with atlas-eval compare"). Deliberately not built in v1: a documented, measurable roadmap item is a better artifact than scope creep.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Create a public GitHub roadmap for Atlas. Add v2/v3 issues for cross-encoder reranking (framed as a benchmark hypothesis measurable with atlas-eval compare), agents, connectors, workflows, multi-tenancy, Kubernetes, observability, OAuth2, OCR, and atlas-ai CLI. Keep v1 closed and focused.
```

**Definition of Done:** Roadmap exists without expanding v1.

### Create demo GIF/video assets — 4h

**Priority:** High  
**Labels:** demo,content

**Description:** Record final demo and create README GIF/screenshots.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Prepare final Atlas demo assets: 2–3 minute video, short GIF for README, and 3 screenshots showing upload, chat with citations, and eval report.
```

**Definition of Done:** Demo assets are embedded/linked in README.

### Write LinkedIn post 1 — 2h

**Priority:** Medium  
**Labels:** content,linkedin

**Description:** Post: Why I built Java-first RAG with Python evals.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Write a LinkedIn post announcing Atlas v1. Theme: why I built a Java-first RAG platform with a Python evaluation harness. Keep tone professional, concrete, and focused on lessons learned.
```

**Definition of Done:** Post draft is ready/published.

### Write LinkedIn post 2 — 2h

**Priority:** Medium  
**Labels:** content,linkedin

**Description:** Post: Hybrid search + RRF benchmark results.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Write a LinkedIn post explaining hybrid search and Reciprocal Rank Fusion in Atlas. Include simple example and benchmark/result from the demo corpus.
```

**Definition of Done:** Post draft is ready/published.

### Write LinkedIn post 3 — 2h

**Priority:** Medium  
**Labels:** content,linkedin

**Description:** Post: Eval-driven RAG development.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Write a LinkedIn post about eval-driven RAG development in Atlas. Explain golden datasets, hit rate, MRR, faithfulness, and why RAG needs regression testing.
```

**Definition of Done:** Post draft is ready/published.

### Update CV and GitHub profile — 3h

**Priority:** High  
**Labels:** career,portfolio

**Description:** Add Atlas line to CV, pin repo, update GitHub README/profile.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Write a concise CV/GitHub profile entry for Atlas: open-source Java/Spring RAG platform with Python evaluation harness, hybrid search, cited generation, FDE playbook. Include metrics once available.
```

**Definition of Done:** CV/GitHub positioning is updated.

### Final release checklist — 3h

**Priority:** High  
**Labels:** release,qa

**Description:** Tag v1.0, verify docs, quickstart, tests, license, no secrets.

**Checklist:**
- [ ] Read card context
- [ ] Use AI prompt
- [ ] Implement change
- [ ] Add/adjust tests
- [ ] Update docs if needed
- [ ] Run local verification
- [ ] Commit with clear message

**AI Prompt:**
```text
Create a final release checklist for Atlas v1. Verify tests, docs, quickstart, no secrets, license, sample data safety, demo video, release notes, and GitHub tag.
```

**Definition of Done:** v1.0 release is public and clean.
