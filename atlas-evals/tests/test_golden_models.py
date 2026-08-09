from pathlib import Path

from atlas_evals.models import ExpectedPageRange, GoldenDataset, GoldenQuestion

DATASET_PATH = Path(__file__).resolve().parent.parent / "datasets" / "mini-golden.json"


def test_loads_the_committed_mini_golden_dataset():
    dataset = GoldenDataset.from_json_file(DATASET_PATH)
    assert dataset.name == "atlas-mini-golden"
    assert dataset.version >= 1
    assert len(dataset.questions) >= 5
    # Ids are unique and stable across phases.
    ids = [q.id for q in dataset.questions]
    assert len(ids) == len(set(ids))


def test_expected_pages_parses_range_list_and_null_forms():
    # {"from": ..., "to": ...} -> ExpectedPageRange
    q_range = GoldenQuestion.model_validate(
        {
            "id": "r",
            "question": "q",
            "expected_document": "gdpr.pdf",
            "expected_pages": {"from": 55, "to": 56},
            "category": "keyword",
            "notes": "",
        }
    )
    assert isinstance(q_range.expected_pages, ExpectedPageRange)
    assert q_range.expected_pages.from_ == 55
    assert q_range.expected_pages.to == 56

    # explicit list of pages -> list[int]
    q_list = GoldenQuestion.model_validate(
        {
            "id": "l",
            "question": "q",
            "expected_document": "ai-act.pdf",
            "expected_pages": [14, 53, 54],
            "category": "hard",
            "notes": "",
        }
    )
    assert q_list.expected_pages == [14, 53, 54]

    # unanswerable -> null document and pages
    q_none = GoldenQuestion.model_validate(
        {
            "id": "n",
            "question": "q",
            "expected_document": None,
            "expected_pages": None,
            "category": "unanswerable",
            "notes": "",
        }
    )
    assert q_none.expected_document is None
    assert q_none.expected_pages is None
