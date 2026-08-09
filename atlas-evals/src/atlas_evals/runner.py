"""Executes a golden dataset against a live Atlas and builds a :class:`RunResults`.

The only I/O layer: it times and issues the HTTP calls (via an injected client), catches per-request
failures so one bad question can't sink the run, and threads multi-turn conversations. Metric math
lives in :mod:`atlas_evals.metrics`; the results schema in :mod:`atlas_evals.results`.
"""

from __future__ import annotations

from collections.abc import Callable, Sequence
from datetime import UTC, datetime
from time import perf_counter
from typing import Protocol
from uuid import UUID

from atlas_evals.errors import AtlasApiError
from atlas_evals.metrics import (
    ChatMetrics,
    RetrievalMetrics,
    aggregate_chat,
    aggregate_retrieval,
    evaluate_chat,
    evaluate_retrieval,
)
from atlas_evals.models.api import (
    ChatResponse,
    HybridSearchResponse,
    SearchFilter,
    SearchHit,
    SearchResponse,
)
from atlas_evals.models.golden import Category, GoldenDataset, GoldenQuestion
from atlas_evals.results import (
    ChatResult,
    HitRecord,
    QuestionError,
    QuestionResult,
    RetrievalEngineResult,
    RunResults,
    RunSummary,
)

RETRIEVAL_ENGINES = ("vector", "keyword", "hybrid")
CHAT_ENGINE = "chat"
ALL_ENGINES = (*RETRIEVAL_ENGINES, CHAT_ENGINE)

ERROR_EXIT_THRESHOLD = 0.20

CROSS_DOCUMENT_NOTE = (
    "Cross-document scoring: page metrics are against the primary (first-listed) expected document "
    "only; secondary documents are credited at the document level."
)
ABSTENTION_NOTE = (
    "Abstention is a deterministic proxy: a chat turn counts as abstained iff it returned zero "
    "citations (the answer text is not inspected)."
)

ProgressCallback = Callable[[str], None]


class AtlasClientProtocol(Protocol):
    """The subset of AtlasClient the runner needs — lets tests inject a fake."""

    def chat(
        self, question: str, *, conversation_id: UUID | None = None, top_k: int | None = None
    ) -> ChatResponse: ...

    def search_vector(
        self, query: str, *, top_k: int | None = None, document_filter: SearchFilter | None = None
    ) -> SearchResponse: ...

    def search_keyword(
        self, query: str, *, top_k: int | None = None, document_filter: SearchFilter | None = None
    ) -> SearchResponse: ...

    def search_hybrid(
        self, query: str, *, top_k: int | None = None, document_filter: SearchFilter | None = None
    ) -> HybridSearchResponse: ...


def _to_error(stage: str, exc: Exception) -> QuestionError:
    status = exc.status_code if isinstance(exc, AtlasApiError) else None
    return QuestionError(stage=stage, message=str(exc), status_code=status)


def _search(
    client: AtlasClientProtocol, engine: str, query: str, top_k: int
) -> Sequence[SearchHit]:
    if engine == "vector":
        return client.search_vector(query, top_k=top_k).results
    if engine == "keyword":
        return client.search_keyword(query, top_k=top_k).results
    if engine == "hybrid":
        return client.search_hybrid(query, top_k=top_k).results
    raise ValueError(f"unknown retrieval engine: {engine}")


def run_dataset(
    client: AtlasClientProtocol,
    dataset: GoldenDataset,
    *,
    base_url: str,
    engines: Sequence[str] = ALL_ENGINES,
    top_k: int = 5,
    progress: ProgressCallback | None = None,
) -> RunResults:
    """Runs the dataset and returns a complete, computed :class:`RunResults`."""
    retrieval_engines = [e for e in engines if e in RETRIEVAL_ENGINES]
    do_chat = CHAT_ENGINE in engines

    started_at = datetime.now(UTC)
    question_results: list[QuestionResult] = []
    retrieval_metrics: dict[str, list[RetrievalMetrics]] = {e: [] for e in retrieval_engines}
    retrieval_latency: dict[str, list[float]] = {e: [] for e in retrieval_engines}
    chat_metrics: list[ChatMetrics] = []
    chat_latency: list[float] = []

    total = len(dataset.questions)
    for index, question in enumerate(dataset.questions, start=1):
        result = QuestionResult(
            id=question.id,
            category=question.category.value,
            question=question.question,
            expected_documents=list(question.expected_documents),
            expected_pages=sorted(question.expected_page_set()),
        )
        answerable = question.category is not Category.UNANSWERABLE

        if answerable:
            for engine in retrieval_engines:
                try:
                    start = perf_counter()
                    hits = _search(client, engine, question.question, top_k)
                    latency_ms = (perf_counter() - start) * 1000.0
                    metrics = evaluate_retrieval(question, hits, top_k)
                    result.retrieval[engine] = RetrievalEngineResult(
                        engine=engine,
                        latency_ms=latency_ms,
                        num_hits=len(hits),
                        hits=[HitRecord.of(rank, hit) for rank, hit in enumerate(hits, start=1)],
                        metrics=metrics,
                    )
                    retrieval_metrics[engine].append(metrics)
                    retrieval_latency[engine].append(latency_ms)
                except Exception as exc:  # noqa: BLE001 — record and continue
                    result.errors.append(_to_error(engine, exc))

        if do_chat:
            _run_chat(client, question, result, chat_metrics, chat_latency)

        question_results.append(result)
        if progress is not None:
            progress(_progress_line(index, total, result))

    finished_at = datetime.now(UTC)
    errored = sum(1 for r in question_results if r.errored)
    summary = RunSummary(
        total_questions=total,
        answerable=sum(1 for q in dataset.questions if q.category is not Category.UNANSWERABLE),
        unanswerable=sum(1 for q in dataset.questions if q.category is Category.UNANSWERABLE),
        errored_questions=errored,
        error_rate=errored / total if total else 0.0,
        engines={
            e: aggregate_retrieval(e, retrieval_metrics[e], retrieval_latency[e])
            for e in retrieval_engines
        },
        chat=aggregate_chat(chat_metrics, chat_latency) if do_chat else None,
        notes=[CROSS_DOCUMENT_NOTE, ABSTENTION_NOTE],
    )
    return RunResults(
        dataset=dataset.name,
        base_url=base_url,
        top_k=top_k,
        engines=list(engines),
        started_at=started_at,
        finished_at=finished_at,
        questions=question_results,
        summary=summary,
    )


def _run_chat(
    client: AtlasClientProtocol,
    question: GoldenQuestion,
    result: QuestionResult,
    chat_metrics: list[ChatMetrics],
    chat_latency: list[float],
) -> None:
    conversation_id: UUID | None = None
    setup_turns_run = 0

    # Replay multi-turn setup on one conversation before the evaluated question.
    if question.setup_turns:
        try:
            for turn in question.setup_turns:
                response = client.chat(turn.question, conversation_id=conversation_id)
                conversation_id = response.conversation_id
                setup_turns_run += 1
        except Exception as exc:  # noqa: BLE001
            result.errors.append(_to_error("setup", exc))
            return  # can't evaluate the follow-up without its context

    try:
        start = perf_counter()
        response = client.chat(question.question, conversation_id=conversation_id)
        latency_ms = (perf_counter() - start) * 1000.0
        metrics = evaluate_chat(question, response.citations)
        result.chat = ChatResult.of(response, latency_ms, setup_turns_run, metrics)
        chat_metrics.append(metrics)
        chat_latency.append(latency_ms)
    except Exception as exc:  # noqa: BLE001
        result.errors.append(_to_error("chat", exc))


def _progress_line(index: int, total: int, result: QuestionResult) -> str:
    parts: list[str] = [f"[{index}/{total}] {result.id} ({result.category})"]
    for engine, engine_result in result.retrieval.items():
        doc = "D" if engine_result.metrics.document_hit else "-"
        page = "P" if engine_result.metrics.page_hit else "-"
        parts.append(f"{engine}:{doc}{page}")
    if result.chat is not None:
        cm = result.chat.metrics
        if cm.answerable:
            parts.append("chat:abstain" if cm.abstained else f"chat:{cm.num_citations}cite")
        else:
            parts.append("chat:abstain✓" if cm.correct_abstention else "chat:LEAK")
    if result.errored:
        parts.append(f"ERRORS={len(result.errors)}")
    return "  ".join(parts)
