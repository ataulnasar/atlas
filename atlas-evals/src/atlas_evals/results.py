"""The results-file schema for ``atlas-eval run`` — the input contract for report/compare.

Versioned via :data:`SCHEMA_VERSION` (bump on any breaking change). Everything a downstream command
needs is here: per-question raw hits and citations, the computed metrics, per-request latency, and
the aggregate per-engine summary. Pure models + record builders; the runner does the I/O.
"""

from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel

from atlas_evals.metrics import ChatMetrics, ChatSummary, EngineSummary, RetrievalMetrics
from atlas_evals.models.api import ChatResponse, ChatUsage, Citation, SearchHit

SCHEMA_VERSION = 1


class HitRecord(BaseModel):
    """One retrieval hit, flattened for the results file."""

    rank: int
    document_filename: str
    start_page: int
    end_page: int
    chunk_index: int
    score: float
    found_by: str | None = None  # hybrid only

    @classmethod
    def of(cls, rank: int, hit: SearchHit) -> HitRecord:
        return cls(
            rank=rank,
            document_filename=hit.document_filename,
            start_page=hit.start_page,
            end_page=hit.end_page,
            chunk_index=hit.chunk_index,
            score=hit.score,
            found_by=getattr(hit, "found_by", None),
        )


class CitationRecord(BaseModel):
    """One cited source, flattened for the results file."""

    citation_id: str
    document_filename: str
    start_page: int
    end_page: int
    chunk_id: str

    @classmethod
    def of(cls, citation: Citation) -> CitationRecord:
        return cls(
            citation_id=citation.citation_id,
            document_filename=citation.document_filename,
            start_page=citation.start_page,
            end_page=citation.end_page,
            chunk_id=str(citation.chunk_id),
        )


class RetrievalEngineResult(BaseModel):
    """A question's result for one retrieval engine."""

    engine: str
    latency_ms: float
    num_hits: int
    hits: list[HitRecord]
    metrics: RetrievalMetrics


class ChatResult(BaseModel):
    """A question's chat result (final turn for multi-turn entries)."""

    latency_ms: float
    conversation_id: str | None
    answer: str
    retrieval_mode: str
    usage: ChatUsage | None
    setup_turns_run: int
    citations: list[CitationRecord]
    metrics: ChatMetrics

    @classmethod
    def of(
        cls,
        response: ChatResponse,
        latency_ms: float,
        setup_turns_run: int,
        metrics: ChatMetrics,
    ) -> ChatResult:
        return cls(
            latency_ms=latency_ms,
            conversation_id=str(response.conversation_id),
            answer=response.answer,
            retrieval_mode=response.retrieval_mode,
            usage=response.usage,
            setup_turns_run=setup_turns_run,
            citations=[CitationRecord.of(c) for c in response.citations],
            metrics=metrics,
        )


class QuestionError(BaseModel):
    """A single failed request within a question (the run continues past it)."""

    stage: str  # "vector" | "keyword" | "hybrid" | "chat" | "setup"
    message: str
    status_code: int | None = None


class QuestionResult(BaseModel):
    """Everything computed for one dataset question."""

    id: str
    category: str
    question: str
    expected_documents: list[str]
    expected_pages: list[int]  # flattened primary-document pages
    retrieval: dict[str, RetrievalEngineResult] = {}
    chat: ChatResult | None = None
    errors: list[QuestionError] = []

    @property
    def errored(self) -> bool:
        return len(self.errors) > 0


class RunSummary(BaseModel):
    """Aggregate over the whole run."""

    total_questions: int
    answerable: int
    unanswerable: int
    errored_questions: int
    error_rate: float
    engines: dict[str, EngineSummary]
    chat: ChatSummary | None
    notes: list[str]


class RunResults(BaseModel):
    """Top-level results file. ``schema_version`` gates report/compare compatibility."""

    schema_version: int = SCHEMA_VERSION
    dataset: str
    base_url: str
    top_k: int
    engines: list[str]
    started_at: datetime
    finished_at: datetime
    questions: list[QuestionResult]
    summary: RunSummary
