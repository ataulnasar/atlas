"""A typed httpx client for the Atlas HTTP API.

Synchronous only — evals call the blocking ``POST /api/chat``; the SSE streaming variant
(``/api/chat/stream``) is intentionally **out of scope** here (the browser UI is its consumer).

Auth: sends ``X-API-Key`` from the ``ATLAS_API_KEY`` environment variable when set, and omits the
header otherwise (keyless-dev servers accept the absent header). Base URL and timeout are
configurable; a custom transport can be injected for tests.
"""

from __future__ import annotations

import os
from types import TracebackType
from uuid import UUID

import httpx
from pydantic import BaseModel

from atlas_evals.errors import AtlasApiError
from atlas_evals.models.api import (
    ChatRequest,
    ChatResponse,
    ChunkView,
    ConversationResponse,
    DocumentStatusResponse,
    HybridSearchResponse,
    SearchFilter,
    SearchRequest,
    SearchResponse,
)

DEFAULT_BASE_URL = "http://localhost:8080"
DEFAULT_TIMEOUT_SECONDS = 30.0
API_KEY_HEADER = "X-API-Key"


class AtlasClient:
    """Typed client for atlas-core. Usable as a context manager (closes the connection pool)."""

    def __init__(
        self,
        base_url: str | None = None,
        *,
        api_key: str | None = None,
        timeout: float = DEFAULT_TIMEOUT_SECONDS,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        resolved_base = base_url or os.environ.get("ATLAS_BASE_URL", DEFAULT_BASE_URL)
        resolved_key = api_key if api_key is not None else os.environ.get("ATLAS_API_KEY")
        headers = {API_KEY_HEADER: resolved_key} if resolved_key else {}
        self._client = httpx.Client(
            base_url=resolved_base,
            timeout=timeout,
            headers=headers,
            transport=transport,
        )

    # --- lifecycle -------------------------------------------------------------------------------

    def close(self) -> None:
        self._client.close()

    def __enter__(self) -> AtlasClient:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.close()

    # --- chat ------------------------------------------------------------------------------------

    def chat(
        self,
        question: str,
        *,
        conversation_id: UUID | None = None,
        top_k: int | None = None,
    ) -> ChatResponse:
        """Ask a question (blocking). Pass ``conversation_id`` to continue a multi-turn thread."""
        payload = ChatRequest(
            question=question, conversation_id=conversation_id, top_k=top_k
        ).model_dump(by_alias=True, exclude_none=True, mode="json")
        return self._post("/api/chat", payload, ChatResponse)

    # --- search ----------------------------------------------------------------------------------

    def search_vector(
        self, query: str, *, top_k: int | None = None, document_filter: SearchFilter | None = None
    ) -> SearchResponse:
        return self._search("vector", query, top_k, document_filter, SearchResponse)

    def search_keyword(
        self, query: str, *, top_k: int | None = None, document_filter: SearchFilter | None = None
    ) -> SearchResponse:
        return self._search("keyword", query, top_k, document_filter, SearchResponse)

    def search_hybrid(
        self, query: str, *, top_k: int | None = None, document_filter: SearchFilter | None = None
    ) -> HybridSearchResponse:
        return self._search("hybrid", query, top_k, document_filter, HybridSearchResponse)

    def _search[M: BaseModel](
        self,
        mode: str,
        query: str,
        top_k: int | None,
        document_filter: SearchFilter | None,
        model: type[M],
    ) -> M:
        payload = SearchRequest(query=query, top_k=top_k, filter=document_filter).model_dump(
            by_alias=True, exclude_none=True, mode="json"
        )
        return self._post(f"/api/search/{mode}", payload, model)

    # --- reads -----------------------------------------------------------------------------------

    def get_chunk(self, chunk_id: UUID) -> ChunkView:
        """Full source text of a chunk (the citation drill-down)."""
        return self._get(f"/api/chunks/{chunk_id}", ChunkView)

    def get_conversation(self, conversation_id: UUID) -> ConversationResponse:
        """The ordered turns of a persisted conversation."""
        return self._get(f"/api/conversations/{conversation_id}", ConversationResponse)

    def get_document(self, document_id: UUID) -> DocumentStatusResponse:
        """A document's ingestion status."""
        return self._get(f"/api/documents/{document_id}", DocumentStatusResponse)

    # --- transport helpers -----------------------------------------------------------------------

    def _post[M: BaseModel](self, path: str, json: dict[str, object], model: type[M]) -> M:
        return self._parse(self._client.post(path, json=json), model)

    def _get[M: BaseModel](self, path: str, model: type[M]) -> M:
        return self._parse(self._client.get(path), model)

    def _parse[M: BaseModel](self, response: httpx.Response, model: type[M]) -> M:
        if response.is_success:
            return model.model_validate(response.json())
        raise AtlasApiError(response.status_code, self._error_body(response))

    @staticmethod
    def _error_body(response: httpx.Response) -> object:
        try:
            return response.json()
        except ValueError:
            return response.text
