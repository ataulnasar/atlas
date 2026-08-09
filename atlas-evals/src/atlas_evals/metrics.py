"""Deterministic retrieval and chat metrics.

Pure functions only — no I/O, no network. They take already-parsed API models plus a
:class:`~atlas_evals.models.golden.GoldenQuestion` and return metric/summary models, so every
number here is unit-testable against hand-computed expectations.

Cross-document scoring (per the dataset's ``cross_document_scoring`` note): document-level metrics
credit ANY expected document; page-level metrics are scored against the PRIMARY (first-listed)
expected document only.

Abstention is a deterministic proxy: a chat turn "abstained" iff it returned zero citations. This
does not read the answer text — it is a structural signal, documented as such.
"""

from __future__ import annotations

from collections.abc import Sequence

from pydantic import BaseModel

from atlas_evals.models.api import Citation, SearchHit
from atlas_evals.models.golden import Category, GoldenQuestion


def page_range_intersects(start_page: int, end_page: int, expected_pages: set[int]) -> bool:
    """True if any page in the inclusive [start_page, end_page] range is an expected page."""
    if not expected_pages:
        return False
    return any(page in expected_pages for page in range(start_page, end_page + 1))


def _mean(values: list[float]) -> float:
    return sum(values) / len(values) if values else 0.0


# --- retrieval -----------------------------------------------------------------------------------


class RetrievalMetrics(BaseModel):
    """Per-question, per-engine retrieval metrics over the returned (top-k) hits."""

    document_hit: bool
    page_hit: bool
    first_relevant_rank: int | None  # 1-based rank of the first page-relevant hit; None if none
    reciprocal_rank: float


def evaluate_retrieval(
    question: GoldenQuestion, hits: Sequence[SearchHit], k: int
) -> RetrievalMetrics:
    """Scores a ranked hit list for an answerable question.

    ``document_hit``: an expected document appears in the top ``k``.
    ``page_hit``: a top-``k`` hit from the PRIMARY expected document intersects the expected pages.
    ``first_relevant_rank`` / ``reciprocal_rank``: rank of the first page-relevant hit (primary
    document + page intersection), computed over the returned list.
    """
    expected_documents = set(question.expected_documents)
    primary = question.expected_documents[0] if question.expected_documents else None
    expected_pages = question.expected_page_set()

    top = hits[:k]
    document_hit = any(hit.document_filename in expected_documents for hit in top)
    page_hit = primary is not None and any(
        hit.document_filename == primary
        and page_range_intersects(hit.start_page, hit.end_page, expected_pages)
        for hit in top
    )

    first_relevant_rank: int | None = None
    for rank, hit in enumerate(top, start=1):
        if (
            primary is not None
            and hit.document_filename == primary
            and page_range_intersects(hit.start_page, hit.end_page, expected_pages)
        ):
            first_relevant_rank = rank
            break

    reciprocal_rank = 1.0 / first_relevant_rank if first_relevant_rank is not None else 0.0
    return RetrievalMetrics(
        document_hit=document_hit,
        page_hit=page_hit,
        first_relevant_rank=first_relevant_rank,
        reciprocal_rank=reciprocal_rank,
    )


class EngineSummary(BaseModel):
    """Aggregate retrieval metrics for one engine across the answerable questions it scored."""

    engine: str
    evaluated: int
    document_hit_rate: float
    page_hit_rate: float
    mrr: float
    avg_latency_ms: float | None


def aggregate_retrieval(
    engine: str, metrics: list[RetrievalMetrics], latencies_ms: list[float]
) -> EngineSummary:
    return EngineSummary(
        engine=engine,
        evaluated=len(metrics),
        document_hit_rate=_mean([1.0 if m.document_hit else 0.0 for m in metrics]),
        page_hit_rate=_mean([1.0 if m.page_hit else 0.0 for m in metrics]),
        mrr=_mean([m.reciprocal_rank for m in metrics]),
        avg_latency_ms=_mean(latencies_ms) if latencies_ms else None,
    )


# --- chat ----------------------------------------------------------------------------------------


class ChatMetrics(BaseModel):
    """Per-question chat metrics derived from the returned citations (abstention = no citations)."""

    answerable: bool
    num_citations: int
    citation_documents_ok: bool | None  # answerable only: >=1 citation and all cited docs expected
    citation_page_hit: bool | None  # answerable only: a citation intersects the primary's pages
    abstained: bool  # citations == []
    false_abstention: bool | None  # answerable only: abstained when it should have answered
    correct_abstention: bool | None  # unanswerable only: abstained as it should


def evaluate_chat(question: GoldenQuestion, citations: list[Citation]) -> ChatMetrics:
    answerable = question.category is not Category.UNANSWERABLE
    abstained = len(citations) == 0

    if not answerable:
        return ChatMetrics(
            answerable=False,
            num_citations=len(citations),
            citation_documents_ok=None,
            citation_page_hit=None,
            abstained=abstained,
            false_abstention=None,
            correct_abstention=abstained,
        )

    expected_documents = set(question.expected_documents)
    primary = question.expected_documents[0] if question.expected_documents else None
    expected_pages = question.expected_page_set()

    citation_documents_ok = len(citations) > 0 and all(
        c.document_filename in expected_documents for c in citations
    )
    citation_page_hit = primary is not None and any(
        c.document_filename == primary
        and page_range_intersects(c.start_page, c.end_page, expected_pages)
        for c in citations
    )
    return ChatMetrics(
        answerable=True,
        num_citations=len(citations),
        citation_documents_ok=citation_documents_ok,
        citation_page_hit=citation_page_hit,
        abstained=abstained,
        false_abstention=abstained,
        correct_abstention=None,
    )


class ChatSummary(BaseModel):
    """Aggregate chat metrics across the dataset."""

    evaluated: int
    answerable_evaluated: int
    unanswerable_evaluated: int
    # over answerable questions that produced >=1 citation:
    citation_document_accuracy: float
    # over all answerable questions:
    citation_page_hit_rate: float
    false_abstention_rate: float
    # over all unanswerable questions:
    abstention_rate: float
    avg_latency_ms: float | None


def aggregate_chat(metrics: list[ChatMetrics], latencies_ms: list[float]) -> ChatSummary:
    answerable = [m for m in metrics if m.answerable]
    unanswerable = [m for m in metrics if not m.answerable]
    with_citations = [m for m in answerable if m.num_citations > 0]

    return ChatSummary(
        evaluated=len(metrics),
        answerable_evaluated=len(answerable),
        unanswerable_evaluated=len(unanswerable),
        citation_document_accuracy=_mean(
            [1.0 if m.citation_documents_ok else 0.0 for m in with_citations]
        ),
        citation_page_hit_rate=_mean([1.0 if m.citation_page_hit else 0.0 for m in answerable]),
        false_abstention_rate=_mean([1.0 if m.false_abstention else 0.0 for m in answerable]),
        abstention_rate=_mean([1.0 if m.correct_abstention else 0.0 for m in unanswerable]),
        avg_latency_ms=_mean(latencies_ms) if latencies_ms else None,
    )
