from pathlib import Path

import pytest
from pydantic import ValidationError

from atlas_evals.models import (
    Category,
    GoldenDataset,
    GoldenQuestion,
    PageRange,
    load_datasets,
)

DATASETS_DIR = Path(__file__).resolve().parent.parent / "datasets"


def _answerable(**overrides: object) -> GoldenQuestion:
    base: dict[str, object] = {
        "id": "x",
        "question": "q",
        "category": "keyword",
        "expected_documents": ["gdpr.pdf"],
        "expected_pages": [1],
    }
    base.update(overrides)
    return GoldenQuestion.model_validate(base)


def test_every_committed_dataset_validates():
    datasets = load_datasets(DATASETS_DIR)
    assert len(datasets) >= 1
    assert any(d.name == "atlas-mini-golden" for d in datasets)


def test_mini_golden_migrated_to_the_new_schema():
    dataset = GoldenDataset.from_json_file(DATASETS_DIR / "mini-golden.json")
    by_id = {q.id: q for q in dataset.questions}

    dpo = by_id["mg-01-keyword-dpo"]
    assert dpo.expected_documents == ["gdpr.pdf"]
    assert dpo.expected_page_set() == {55, 56}  # a {from,to} range flattens

    high_risk = by_id["mg-06-hard-aiact-high-risk"]
    assert high_risk.expected_page_set() == {14, 53, 54, 55, 56}
    assert high_risk.expected_chunk_indexes == [38, 138, 139, 141, 142]

    unanswerable = by_id["mg-05-unanswerable-water-intake"]
    assert unanswerable.category is Category.UNANSWERABLE
    assert unanswerable.expected_documents == []
    assert unanswerable.expected_pages == []


def test_expected_pages_mixes_numbers_and_ranges():
    q = _answerable(expected_pages=[14, {"from": 54, "to": 56}])
    assert isinstance(q.expected_pages[1], PageRange)
    assert q.expected_page_set() == {14, 54, 55, 56}


def test_singular_expected_document_is_accepted():
    q = GoldenQuestion.model_validate(
        {"id": "s", "question": "q", "category": "keyword",
         "expected_document": "gdpr.pdf", "expected_pages": [1]}
    )
    assert q.expected_documents == ["gdpr.pdf"]


def test_cross_document_supports_multiple_documents():
    q = _answerable(category="cross-document", expected_documents=["nis2.pdf", "dora.pdf"])
    assert q.expected_documents == ["nis2.pdf", "dora.pdf"]


def test_multi_turn_setup_turns_parse():
    q = _answerable(
        category="multi-turn",
        setup_turns=[{"question": "What is a DPO?"}],
    )
    assert q.setup_turns is not None
    assert q.setup_turns[0].question == "What is a DPO?"


def test_page_level_matching_is_mandatory_for_answerable_entries():
    with pytest.raises(ValidationError, match="page-level matching is mandatory"):
        GoldenQuestion.model_validate(
            {"id": "np", "question": "q", "category": "keyword", "expected_documents": ["gdpr.pdf"]}
        )


def test_unanswerable_must_not_carry_expected_sources():
    with pytest.raises(ValidationError, match="unanswerable"):
        GoldenQuestion.model_validate(
            {"id": "u", "question": "q", "category": "unanswerable",
             "expected_documents": ["gdpr.pdf"], "expected_pages": [1]}
        )


def test_duplicate_ids_are_rejected():
    with pytest.raises(ValidationError, match="duplicate question ids"):
        GoldenDataset.model_validate(
            {
                "dataset": "d",
                "version": 1,
                "questions": [
                    {"id": "dup", "question": "a", "category": "keyword",
                     "expected_documents": ["x.pdf"], "expected_pages": [1]},
                    {"id": "dup", "question": "b", "category": "keyword",
                     "expected_documents": ["y.pdf"], "expected_pages": [2]},
                ],
            }
        )


def test_page_range_rejects_reversed_bounds():
    with pytest.raises(ValidationError):
        PageRange.model_validate({"from": 10, "to": 5})
