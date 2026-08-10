"""An in-process mock Atlas for the CI harness smoke test.

Serves canned, deterministic responses for the ``/api/search/{vector,keyword,hybrid}`` and
``/api/chat`` endpoints so the whole eval pipeline (run -> results -> report -> compare) can run on
every PR with no OpenAI key, no running stack, and no Docker. Responses are derived from the golden
dataset itself (keyed by the question text the runner sends), so they can't drift from the dataset.

Behaviour per question:
- unanswerable: chat returns no citations (retrieval isn't called for unanswerables).
- ``cs-miss`` (id): retrieval and chat both return a wrong document -> a deliberate miss.
- semantic: the keyword engine returns nothing (mirrors keyword-collapse); vector/hybrid hit.
- otherwise: every engine and chat return the expected document + page range -> a full hit.
"""

from __future__ import annotations

import json
import threading
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from uuid import UUID

from atlas_evals.models.api import (
    ChatResponse,
    ChatUsage,
    Citation,
    HybridSearchHit,
    HybridSearchResponse,
    SearchHit,
    SearchResponse,
)
from atlas_evals.models.golden import Category, GoldenDataset, GoldenQuestion

_FIXED_ID = UUID("00000000-0000-0000-0000-0000000000aa")
_WRONG_DOC = "wrong.pdf"
_MISS_ID = "cs-miss"

JsonDict = dict[str, object]


@dataclass
class Canned:
    """Canned responses keyed by (engine, query) for search and by question text for chat."""

    search: dict[tuple[str, str], JsonDict]
    chat: dict[str, JsonDict]


def _citation(doc: str, start: int, end: int) -> Citation:
    return Citation(
        citation_id="c1",
        chunk_id=_FIXED_ID,
        document_id=_FIXED_ID,
        document_filename=doc,
        document_title=doc,
        start_page=start,
        end_page=end,
        snippet="…",
    )


def _search_hit(doc: str, start: int, end: int) -> SearchHit:
    return SearchHit.model_validate(
        {**_citation(doc, start, end).model_dump(by_alias=True), "chunkIndex": 0, "score": 0.9}
    )


def _hybrid_of(hits: list[SearchHit]) -> HybridSearchResponse:
    fusion = {"foundBy": "both", "vectorRank": 1, "keywordRank": 1}
    return HybridSearchResponse(
        results=[
            HybridSearchHit.model_validate({**h.model_dump(by_alias=True), **fusion}) for h in hits
        ]
    )


def _hit_target(question: GoldenQuestion) -> tuple[str, int, int]:
    """The (document, start, end) a full hit should return for an answerable question."""
    doc = question.expected_documents[0]
    pages = sorted(question.expected_page_set())
    return doc, pages[0], pages[-1]


def build_canned(dataset: GoldenDataset) -> Canned:
    search: dict[tuple[str, str], JsonDict] = {}
    chat: dict[str, JsonDict] = {}

    for question in dataset.questions:
        query = question.question
        if question.category is Category.UNANSWERABLE:
            # No retrieval is issued for unanswerables; chat abstains (no citations).
            chat[query] = ChatResponse(
                conversation_id=_FIXED_ID, answer="The documents do not cover this.",
                citations=[], retrieval_mode="hybrid", usage=_usage(),
            ).model_dump(by_alias=True, mode="json")
            continue

        if question.id == _MISS_ID:
            miss = [_search_hit(_WRONG_DOC, 1, 2)]
            search[("vector", query)] = SearchResponse(results=miss).model_dump(
                by_alias=True, mode="json"
            )
            search[("keyword", query)] = SearchResponse(results=miss).model_dump(
                by_alias=True, mode="json"
            )
            search[("hybrid", query)] = _hybrid_of(miss).model_dump(by_alias=True, mode="json")
            chat[query] = ChatResponse(
                conversation_id=_FIXED_ID, answer="Off-target answer.",
                citations=[_citation(_WRONG_DOC, 1, 2)], retrieval_mode="hybrid", usage=_usage(),
            ).model_dump(by_alias=True, mode="json")
            continue

        doc, start, end = _hit_target(question)
        hit = [_search_hit(doc, start, end)]
        empty = SearchResponse(results=[]).model_dump(by_alias=True, mode="json")
        search[("vector", query)] = SearchResponse(results=hit).model_dump(
            by_alias=True, mode="json"
        )
        # Semantic questions: keyword engine returns nothing (keyword-collapse), others hit.
        search[("keyword", query)] = (
            empty
            if question.category is Category.SEMANTIC
            else SearchResponse(results=hit).model_dump(by_alias=True, mode="json")
        )
        search[("hybrid", query)] = _hybrid_of(hit).model_dump(by_alias=True, mode="json")
        chat[query] = ChatResponse(
            conversation_id=_FIXED_ID, answer=f"See {doc}.",
            citations=[_citation(doc, start, end)], retrieval_mode="hybrid", usage=_usage(),
        ).model_dump(by_alias=True, mode="json")

    return Canned(search=search, chat=chat)


def _usage() -> ChatUsage:
    return ChatUsage(
        prompt_tokens=100, completion_tokens=20, total_tokens=120, model="gpt-5-mini-ci"
    )


class _MockServer(ThreadingHTTPServer):
    canned: Canned


class _Handler(BaseHTTPRequestHandler):
    def do_POST(self) -> None:  # noqa: N802 (BaseHTTPRequestHandler API)
        assert isinstance(self.server, _MockServer)
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length) or b"{}")
        canned = self.server.canned

        if self.path.endswith("/api/chat"):
            question = str(body.get("question", ""))
            payload = canned.chat.get(question, {"error": "no canned response"})
        elif "/api/search/" in self.path:
            engine = self.path.rstrip("/").rsplit("/", 1)[-1]
            payload = canned.search.get(
                (engine, str(body.get("query", ""))), {"results": []}
            )
        else:
            self.send_error(404, "unknown endpoint")
            return

        data = json.dumps(payload).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args: object) -> None:  # silence per-request logging
        return


@contextmanager
def mock_atlas(dataset: GoldenDataset) -> Iterator[str]:
    """Runs the mock server on an ephemeral port; yields its base URL."""
    server = _MockServer(("127.0.0.1", 0), _Handler)
    server.canned = build_canned(dataset)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_address[1]}"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
