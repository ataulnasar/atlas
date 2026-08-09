# atlas-ui

A minimal React + Vite + TypeScript chat page for Atlas. One screen: ask a question about the
ingested EU digital-regulation corpus and read a streamed, citation-grounded answer, with a
click-through to the exact source passage behind each `[cN]`.

It talks to `atlas-core` over the same HTTP API everything else uses — no bespoke backend.

## Prerequisites

- Node 20+ and npm.
- A running `atlas-core` on `http://localhost:8080` **with generation enabled** (an OpenAI API key
  configured in `docker/.env`). Streaming chat needs the `POST /api/chat/stream` endpoint; in
  keyless mode the endpoint returns `503 generation_disabled` and the UI shows that inline.

Start the stack per the repo root / `docker/` README, then confirm:

```bash
curl -s -X POST http://localhost:8080/api/search/hybrid \
  -H 'Content-Type: application/json' -d '{"query":"data protection officer","topK":1}'
```

## Develop

```bash
npm install
npm run dev
```

Open the printed URL (default `http://localhost:5173`). Vite proxies `/api/*` to
`http://localhost:8080` (see `vite.config.ts`), so the browser sees a single origin and **no CORS
configuration is needed** anywhere in dev.

## Scripts

| Command             | What it does                                          |
| ------------------- | ----------------------------------------------------- |
| `npm run dev`       | Vite dev server with hot reload + the `/api` proxy    |
| `npm run build`     | Type-check (`tsc --noEmit`) then production `vite build` |
| `npm run preview`   | Serve the built `dist/` locally                       |
| `npm test`          | Run the unit tests (Vitest)                           |
| `npm run typecheck` | Type-check only                                       |

## API key

If the `atlas-core` server requires an API key (`ATLAS_API_KEY` set), enter it via the **gear icon**
(top right). It's stored in `localStorage` and sent as `X-API-Key` on every request, including the
SSE fetch. A `401` renders as an inline notice prompting you to add the key.

Against a **keyless-dev** server (no `ATLAS_API_KEY`), leave it empty — no header is sent and it just
works. The proxy and app need no auth configuration either way.

## What's tested

Deliberately minimal, per the card scope: the tricky, logic-bearing parts are unit-tested, and
component/DOM testing is out of v1 scope.

- `src/api/sse.test.ts` — the SSE decoder: event/data field parsing, and correct reassembly of
  events split across arbitrary fetch-chunk boundaries (the thing that silently breaks otherwise).
- `src/state/chat.test.ts` — the streaming **swap**: raw token deltas accumulate with the model's
  original `[cN]` markers, then the `citations` event swaps in the renumbered answer and attaches
  the cited subset that the chips render from.
- `src/api/apiKey.test.ts` — the auth header is sent only when a key is set (absent otherwise, so
  keyless-dev backends accept the request).

## How the stream is consumed

`EventSource` can't issue a POST, so `src/api/chatClient.ts` POSTs with `fetch` and reads
`response.body` as a stream, decoding `text/event-stream` frames incrementally
(`src/api/sse.ts`). Events: repeated `token` (raw deltas), then one `citations` (renumbered answer
+ cited sources), then one `done` (usage + `conversationId`, kept for multi-turn). A mid-stream
`error` event, and pre-stream `400`/`503` responses, render as inline notices — never as alerts.

## Not in this card

**Docker / production packaging is intentionally out of scope here** — the deployment-profile card
owns building and serving `dist/` (and pointing it at a real `atlas-core` origin instead of the dev
proxy). This module is dev-server-only for now.

The citation side panel is the **basic** drill-down (full source text, filename, page range). The
dedicated "Add citation display in UI" card can enrich it (highlighting the cited span, navigating
to neighbouring chunks, etc.).
