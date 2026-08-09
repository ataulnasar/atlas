from uuid import uuid4

from atlas_evals.metrics import (
    aggregate_chat,
    aggregate_retrieval,
    evaluate_chat,
    evaluate_retrieval,
    page_range_intersects,
)
from atlas_evals.models.api import Citation, HybridSearchHit, SearchHit
from atlas_evals.models.golden import GoldenQuestion


def question(
    category: str = "keyword",
    docs: list[str] | None = None,
    pages: list[object] | None = None,
    **extra: object,
) -> GoldenQuestion:
    data: dict[str, object] = {
        "id": "t",
        "question": "q?",
        "category": category,
        "expected_documents": ["gdpr.pdf"] if docs is None else docs,
        "expected_pages": [55, 56] if pages is None else pages,
    }
    data.update(extra)
    return GoldenQuestion.model_validate(data)


def hit(doc: str, start: int, end: int, score: float = 0.5, idx: int = 0) -> SearchHit:
    return SearchHit.model_validate(
        {
            "citationId": "c",
            "chunkId": str(uuid4()),
            "documentId": str(uuid4()),
            "documentFilename": doc,
            "documentTitle": doc,
            "startPage": start,
            "endPage": end,
            "snippet": "…",
            "chunkIndex": idx,
            "score": score,
        }
    )


def cite(doc: str, start: int, end: int) -> Citation:
    return Citation.model_validate(
        {
            "citationId": "c1",
            "chunkId": str(uuid4()),
            "documentId": str(uuid4()),
            "documentFilename": doc,
            "documentTitle": doc,
            "startPage": start,
            "endPage": end,
            "snippet": "…",
        }
    )


# --- page intersection edge cases ----------------------------------------------------------------


def test_page_intersection_boundary_and_empty():
    assert page_range_intersects(55, 56, {56}) is True  # end touches boundary
    assert page_range_intersects(56, 57, {56}) is True  # start touches boundary
    assert page_range_intersects(57, 58, {56}) is False  # just past
    assert page_range_intersects(54, 55, {56}) is False  # just before
    assert page_range_intersects(1, 100, set()) is False  # no expected pages


def test_page_intersection_multi_range_expected():
    q = question(pages=[14, {"from": 54, "to": 56}])
    assert q.expected_page_set() == {14, 54, 55, 56}
    assert page_range_intersects(13, 14, q.expected_page_set()) is True
    assert page_range_intersects(15, 20, q.expected_page_set()) is False
    assert page_range_intersects(56, 60, q.expected_page_set()) is True


# --- retrieval metrics ---------------------------------------------------------------------------


def test_retrieval_document_and_page_hit_and_rank():
    q = question(docs=["gdpr.pdf"], pages=[55, 56])
    hits = [
        hit("other.pdf", 1, 2),  # rank 1: wrong doc
        hit("gdpr.pdf", 10, 11),  # rank 2: right doc, wrong page
        hit("gdpr.pdf", 55, 56),  # rank 3: right doc + page
    ]
    m = evaluate_retrieval(q, hits, k=5)
    assert m.document_hit is True
    assert m.page_hit is True
    assert m.first_relevant_rank == 3
    assert m.reciprocal_rank == 1 / 3


def test_retrieval_page_hit_respects_top_k():
    q = question(pages=[55, 56])
    hits = [hit("gdpr.pdf", 1, 2), hit("gdpr.pdf", 55, 56)]
    assert evaluate_retrieval(q, hits, k=1).page_hit is False  # relevant hit is at rank 2
    assert evaluate_retrieval(q, hits, k=2).page_hit is True


def test_retrieval_miss_gives_zero_reciprocal_rank():
    q = question(docs=["gdpr.pdf"], pages=[55, 56])
    m = evaluate_retrieval(q, [hit("dma.pdf", 1, 2)], k=5)
    assert m.document_hit is False
    assert m.page_hit is False
    assert m.first_relevant_rank is None
    assert m.reciprocal_rank == 0.0


def test_retrieval_cross_document_pages_scored_against_primary_only():
    # primary = nis2.pdf; dora.pdf is a secondary (document-level credit only).
    q = question(category="cross-document", docs=["nis2.pdf", "dora.pdf"], pages=[50, 51])
    hits = [hit("dora.pdf", 50, 51)]  # secondary doc, on the expected pages
    m = evaluate_retrieval(q, hits, k=5)
    assert m.document_hit is True  # dora is an expected document
    assert m.page_hit is False  # but pages are scored only against nis2 (primary)
    assert m.first_relevant_rank is None


def test_hybrid_hits_are_accepted():
    q = question(pages=[55, 56])
    hyb = HybridSearchHit.model_validate(
        {
            "citationId": "c", "chunkId": str(uuid4()), "documentId": str(uuid4()),
            "documentFilename": "gdpr.pdf", "documentTitle": "gdpr.pdf",
            "startPage": 55, "endPage": 56, "snippet": "…", "chunkIndex": 1,
            "score": 0.03, "foundBy": "both", "vectorRank": 2, "keywordRank": 3,
        }
    )
    assert evaluate_retrieval(q, [hyb], k=5).page_hit is True


# --- chat metrics --------------------------------------------------------------------------------


def test_chat_answerable_correct_citation():
    q = question(docs=["gdpr.pdf"], pages=[55, 56])
    m = evaluate_chat(q, [cite("gdpr.pdf", 55, 56)])
    assert m.citation_documents_ok is True
    assert m.citation_page_hit is True
    assert m.abstained is False
    assert m.false_abstention is False


def test_chat_answerable_false_abstention_on_empty_citations():
    q = question()
    m = evaluate_chat(q, [])
    assert m.abstained is True
    assert m.false_abstention is True
    assert m.citation_documents_ok is False
    assert m.citation_page_hit is False


def test_chat_answerable_wrong_document():
    q = question(docs=["gdpr.pdf"], pages=[55, 56])
    m = evaluate_chat(q, [cite("dma.pdf", 55, 56)])
    assert m.citation_documents_ok is False
    assert m.citation_page_hit is False
    assert m.false_abstention is False  # it did cite something


def test_chat_unanswerable_correct_and_leak():
    q = question(category="unanswerable", docs=[], pages=[])
    correct = evaluate_chat(q, [])
    assert correct.answerable is False
    assert correct.correct_abstention is True
    assert correct.false_abstention is None

    leak = evaluate_chat(q, [cite("gdpr.pdf", 1, 2)])
    assert leak.correct_abstention is False


# --- aggregates ----------------------------------------------------------------------------------


def test_aggregate_retrieval_rates_and_mrr():
    q = question(pages=[55, 56])
    m_hit = evaluate_retrieval(q, [hit("gdpr.pdf", 55, 56)], k=5)  # rank 1, page hit
    m_rank2 = evaluate_retrieval(q, [hit("gdpr.pdf", 1, 2), hit("gdpr.pdf", 55, 56)], k=5)  # rank 2
    m_miss = evaluate_retrieval(q, [hit("dma.pdf", 1, 2)], k=5)  # miss

    summary = aggregate_retrieval("vector", [m_hit, m_rank2, m_miss], [10.0, 20.0, 30.0])
    assert summary.evaluated == 3
    assert summary.document_hit_rate == 2 / 3  # two hit the right doc
    assert summary.page_hit_rate == 2 / 3
    assert summary.mrr == (1.0 + 0.5 + 0.0) / 3
    assert summary.avg_latency_ms == 20.0


def test_aggregate_chat_rates():
    answerable = question(docs=["gdpr.pdf"], pages=[55, 56])
    unanswerable = question(category="unanswerable", docs=[], pages=[])
    metrics = [
        evaluate_chat(answerable, [cite("gdpr.pdf", 55, 56)]),  # good
        evaluate_chat(answerable, []),  # false abstention
        evaluate_chat(unanswerable, []),  # correct abstention
        evaluate_chat(unanswerable, [cite("gdpr.pdf", 1, 2)]),  # leak
    ]
    s = aggregate_chat(metrics, [5.0, 5.0, 5.0, 5.0])
    assert s.answerable_evaluated == 2
    assert s.unanswerable_evaluated == 2
    # only 1 answerable produced citations, and it was correct
    assert s.citation_document_accuracy == 1.0
    assert s.citation_page_hit_rate == 0.5  # 1 of 2 answerable page-hit
    assert s.false_abstention_rate == 0.5  # 1 of 2 answerable abstained
    assert s.abstention_rate == 0.5  # 1 of 2 unanswerable abstained
