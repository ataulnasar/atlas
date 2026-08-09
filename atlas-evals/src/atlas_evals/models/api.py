"""Pydantic models mirroring atlas-core's HTTP JSON contract.

Field names are verified against the Java records and their serialization tests
(``CitationSerializationTest``, ``ChatSchemaSerializationTest``) — the wire is camelCase, so a
shared :class:`CamelModel` base maps snake_case attributes to camelCase aliases. Search hits embed
the citation *flat* (``@JsonUnwrapped`` in Java), so :class:`SearchHit` inherits :class:`Citation`.
"""

from __future__ import annotations

from datetime import datetime
from enum import StrEnum
from uuid import UUID

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel


class CamelModel(BaseModel):
    """Base model: snake_case attributes, camelCase JSON aliases (both accepted on input)."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


# --- responses -----------------------------------------------------------------------------------


class Citation(CamelModel):
    """A cited source (matches the Java ``Citation`` record exactly)."""

    citation_id: str
    chunk_id: UUID
    document_id: UUID
    document_filename: str
    document_title: str
    start_page: int
    end_page: int
    snippet: str


class ChatUsage(CamelModel):
    """Provider token accounting; every field may be null."""

    prompt_tokens: int | None = None
    completion_tokens: int | None = None
    total_tokens: int | None = None
    model: str | None = None


class ChatResponse(CamelModel):
    """Response body of ``POST /api/chat``."""

    conversation_id: UUID
    answer: str
    citations: list[Citation]
    retrieval_mode: str
    usage: ChatUsage


class SearchHit(Citation):
    """A ranked hit: the citation embedded flat, plus the search envelope."""

    chunk_index: int
    score: float


class HybridSearchHit(SearchHit):
    """A hybrid hit: adds the RRF fusion-transparency fields."""

    found_by: str
    vector_rank: int | None = None
    keyword_rank: int | None = None


class SearchResponse(CamelModel):
    """Response body of ``POST /api/search/{vector,keyword}``."""

    results: list[SearchHit]


class HybridSearchResponse(CamelModel):
    """Response body of ``POST /api/search/hybrid``."""

    results: list[HybridSearchHit]


class ChunkView(CamelModel):
    """Response body of ``GET /api/chunks/{id}`` — the full source passage."""

    chunk_id: UUID
    document_id: UUID
    document_filename: str
    document_title: str
    chunk_index: int
    start_page: int
    end_page: int
    content: str


class ConversationMessageView(CamelModel):
    """One persisted turn in a conversation read-back. ``citations`` is null for user turns."""

    role: str
    content: str
    citations: list[Citation] | None = None
    created_at: datetime


class ConversationResponse(CamelModel):
    """Response body of ``GET /api/conversations/{id}`` — the ordered turns."""

    conversation_id: UUID
    messages: list[ConversationMessageView]


class DocumentStatus(StrEnum):
    """Ingestion lifecycle state (matches the Java ``DocumentStatus`` enum)."""

    PENDING = "PENDING"
    PROCESSING = "PROCESSING"
    READY = "READY"
    FAILED = "FAILED"


class DocumentStatusResponse(CamelModel):
    """Response body of ``GET /api/documents/{id}``."""

    id: UUID
    filename: str
    status: DocumentStatus
    error_message: str | None = None
    chunk_count: int


class ApiErrorBody(CamelModel):
    """The clean error body atlas-core returns for 4xx/5xx (``{error, message}``)."""

    error: str
    message: str


# --- requests ------------------------------------------------------------------------------------


class SearchFilter(CamelModel):
    """Optional document filter for search (by id and/or filename)."""

    document_ids: list[UUID] | None = None
    filenames: list[str] | None = None


class SearchRequest(CamelModel):
    """Request body for the search endpoints."""

    query: str
    top_k: int | None = None
    filter: SearchFilter | None = None


class ChatRequest(CamelModel):
    """Request body for ``POST /api/chat``."""

    question: str
    conversation_id: UUID | None = None
    top_k: int | None = None
