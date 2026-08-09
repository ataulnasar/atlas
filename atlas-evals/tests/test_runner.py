from uuid import UUID, uuid4

from atlas_evals.errors import AtlasApiError
from atlas_evals.models.api import (
    ChatResponse,
    ChatUsage,
    Citation,
    HybridSearchHit,
    HybridSearchResponse,
    SearchFilter,
    SearchHit,
    SearchResponse,
)
from atlas_evals.models.golden import GoldenDataset
from atlas_evals.runner import run_dataset


def make_hit(doc: str, start: int, end: int, idx: int = 0) -> SearchHit:
    return SearchHit.model_validate(
        {
            "citationId": "c", "chunkId": str(uuid4()), "documentId": str(uuid4()),
            "documentFilename": doc, "documentTitle": doc, "startPage": start, "endPage": end,
            "snippet": "…", "chunkIndex": idx, "score": 0.5,
        }
    )


def make_cite(doc: str, start: int, end: int) -> Citation:
    return Citation.model_validate(
        {
            "citationId": "c1", "chunkId": str(uuid4()), "documentId": str(uuid4()),
            "documentFilename": doc, "documentTitle": doc, "startPage": start, "endPage": end,
            "snippet": "…",
        }
    )


class FakeClient:
    """Configurable stand-in for AtlasClient (structurally matches the runner's protocol)."""

    def __init__(
        self,
        hits: dict[str, list[SearchHit]] | None = None,
        citations: dict[str, list[Citation]] | None = None,
        fail_search_queries: set[str] | None = None,
        fail_chat_questions: set[str] | None = None,
    ) -> None:
        self._hits = hits or {}
        self._citations = citations or {}
        self._fail_search = fail_search_queries or set()
        self._fail_chat = fail_chat_questions or set()
        # (question, conversation_id_in, conversation_id_out)
        self.chat_calls: list[tuple[str, UUID | None, UUID]] = []

    def _search(self, query: str) -> list[SearchHit]:
        if query in self._fail_search:
            raise AtlasApiError(500, {"error": "server_error", "message": "boom"})
        return self._hits.get(query, [])

    def search_vector(
        self, query: str, *, top_k: int | None = None, document_filter: SearchFilter | None = None
    ) -> SearchResponse:
        return SearchResponse(results=self._search(query))

    def search_keyword(
        self, query: str, *, top_k: int | None = None, document_filter: SearchFilter | None = None
    ) -> SearchResponse:
        return SearchResponse(results=self._search(query))

    def search_hybrid(
        self, query: str, *, top_k: int | None = None, document_filter: SearchFilter | None = None
    ) -> HybridSearchResponse:
        # Promote the configured SearchHits to HybridSearchHits with fusion fields.
        fusion = {"foundBy": "both", "vectorRank": 1, "keywordRank": 1}
        hybrid = [
            HybridSearchHit.model_validate({**h.model_dump(by_alias=True), **fusion})
            for h in self._search(query)
        ]
        return HybridSearchResponse(results=hybrid)

    def chat(
        self, question: str, *, conversation_id: UUID | None = None, top_k: int | None = None
    ) -> ChatResponse:
        if question in self._fail_chat:
            raise AtlasApiError(504, {"error": "timeout", "message": "slow"})
        conversation_out = conversation_id or uuid4()
        self.chat_calls.append((question, conversation_id, conversation_out))
        return ChatResponse(
            conversation_id=conversation_out,
            answer="answer",
            citations=self._citations.get(question, []),
            retrieval_mode="hybrid",
            usage=ChatUsage(prompt_tokens=10, completion_tokens=5, total_tokens=15, model="fake"),
        )


def dataset(*questions: dict[str, object]) -> GoldenDataset:
    return GoldenDataset.model_validate(
        {"dataset": "test", "version": 1, "questions": list(questions)}
    )


def test_happy_path_computes_metrics_for_all_engines_and_chat():
    ds = dataset(
        {"id": "q1", "question": "dpo tasks?", "category": "keyword",
         "expected_documents": ["gdpr.pdf"], "expected_pages": [55, 56]},
    )
    client = FakeClient(
        hits={"dpo tasks?": [make_hit("gdpr.pdf", 55, 56)]},
        citations={"dpo tasks?": [make_cite("gdpr.pdf", 55, 56)]},
    )
    results = run_dataset(client, ds, base_url="http://x", top_k=5)

    assert results.summary.total_questions == 1
    assert results.summary.errored_questions == 0
    q = results.questions[0]
    assert set(q.retrieval) == {"vector", "keyword", "hybrid"}
    for engine_result in q.retrieval.values():
        assert engine_result.metrics.page_hit is True
        assert engine_result.latency_ms >= 0.0
    assert q.chat is not None
    assert q.chat.metrics.citation_page_hit is True
    assert results.summary.engines["vector"].page_hit_rate == 1.0
    assert results.summary.chat is not None
    assert results.summary.chat.citation_page_hit_rate == 1.0


def test_unanswerable_skips_retrieval_and_accounts_abstention():
    ds = dataset(
        {"id": "u1", "question": "capital of France?", "category": "unanswerable",
         "expected_documents": [], "expected_pages": []},
    )
    client = FakeClient(citations={"capital of France?": []})  # abstains
    results = run_dataset(client, ds, base_url="http://x")

    q = results.questions[0]
    assert q.retrieval == {}  # no retrieval for unanswerable
    assert q.chat is not None
    assert q.chat.metrics.correct_abstention is True
    assert results.summary.chat is not None
    assert results.summary.chat.abstention_rate == 1.0
    assert results.summary.errored_questions == 0


def test_one_question_errors_and_run_continues():
    ds = dataset(
        {"id": "ok", "question": "good?", "category": "keyword",
         "expected_documents": ["gdpr.pdf"], "expected_pages": [1]},
        {"id": "bad", "question": "boom?", "category": "keyword",
         "expected_documents": ["gdpr.pdf"], "expected_pages": [1]},
    )
    client = FakeClient(
        hits={"good?": [make_hit("gdpr.pdf", 1, 1)]},
        fail_search_queries={"boom?"},
        fail_chat_questions={"boom?"},
    )
    results = run_dataset(client, ds, base_url="http://x", engines=["vector", "chat"])

    good = next(q for q in results.questions if q.id == "ok")
    bad = next(q for q in results.questions if q.id == "bad")
    assert good.errored is False
    assert bad.errored is True
    stages = {e.stage for e in bad.errors}
    assert "vector" in stages and "chat" in stages
    assert bad.errors[0].status_code in {500, 504}
    assert results.summary.errored_questions == 1
    assert results.summary.error_rate == 0.5  # 1 of 2


def test_multiturn_threads_one_conversation():
    ds = dataset(
        {
            "id": "mt", "question": "who must appoint one?", "category": "multi-turn",
            "expected_documents": ["gdpr.pdf"], "expected_pages": [54, 55],
            "setup_turns": [{"question": "what are the DPO tasks?"}],
        },
    )
    client = FakeClient(citations={"who must appoint one?": [make_cite("gdpr.pdf", 54, 55)]})
    results = run_dataset(client, ds, base_url="http://x", engines=["chat"])

    q = results.questions[0]
    assert q.chat is not None
    assert q.chat.setup_turns_run == 1
    # Two chat calls: the setup turn (fresh conversation) then the follow-up on the SAME id.
    assert len(client.chat_calls) == 2
    setup_q, setup_in, setup_out = client.chat_calls[0]
    final_q, final_in, _ = client.chat_calls[1]
    assert setup_q == "what are the DPO tasks?" and setup_in is None
    assert final_q == "who must appoint one?" and final_in == setup_out


def test_progress_callback_receives_a_line_per_question():
    ds = dataset(
        {"id": "q1", "question": "a?", "category": "keyword",
         "expected_documents": ["gdpr.pdf"], "expected_pages": [1]},
        {"id": "q2", "question": "b?", "category": "keyword",
         "expected_documents": ["gdpr.pdf"], "expected_pages": [1]},
    )
    client = FakeClient()
    lines: list[str] = []
    run_dataset(client, ds, base_url="http://x", engines=["chat"], progress=lines.append)
    assert len(lines) == 2
    assert lines[0].startswith("[1/2] q1")
