from pathlib import Path

from atlas_evals.client import AtlasClient
from atlas_evals.models.golden import Category, GoldenDataset
from atlas_evals.runner import run_dataset
from atlas_evals.testing import build_canned, mock_atlas

DATASET_PATH = Path(__file__).resolve().parent.parent / "datasets" / "ci-smoke.json"


def _dataset() -> GoldenDataset:
    return GoldenDataset.from_json_file(DATASET_PATH)


def test_mock_covers_every_dataset_question():
    dataset = _dataset()
    canned = build_canned(dataset)
    for q in dataset.questions:
        assert q.question in canned.chat  # every question has a canned chat response
        if q.category is not Category.UNANSWERABLE:
            assert ("vector", q.question) in canned.search  # answerables have retrieval responses


def test_pipeline_against_mock_produces_expected_metrics():
    dataset = _dataset()
    with mock_atlas(dataset) as base_url:
        results = run_dataset(AtlasClient(base_url=base_url), dataset, base_url=base_url, top_k=5)

    assert results.summary.errored_questions == 0
    engines = results.summary.engines
    # keyword + semantic hit on vector/hybrid, cs-miss misses -> 2/3 page hit.
    assert engines["vector"].page_hit_rate == 2 / 3
    assert engines["hybrid"].page_hit_rate == 2 / 3
    # keyword engine: only the keyword question hits (semantic returns empty, cs-miss wrong) -> 1/3.
    assert engines["keyword"].page_hit_rate == 1 / 3

    chat = results.summary.chat
    assert chat is not None
    # keyword + semantic cite correctly; cs-miss cites the wrong doc -> 2/3.
    assert chat.citation_document_accuracy == 2 / 3
    assert chat.abstention_rate == 1.0  # the single unanswerable abstains


def test_smoke_script_passes():
    # Import lazily so the module path is exercised end-to-end (run -> report -> compare).
    import subprocess
    import sys

    result = subprocess.run(
        [sys.executable, "scripts/ci_smoke.py"],
        cwd=DATASET_PATH.parent.parent,
        capture_output=True,
        text=True,
        check=False,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    assert "PASS" in result.stdout
