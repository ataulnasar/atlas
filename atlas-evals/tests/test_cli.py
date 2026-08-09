import json
from pathlib import Path
from uuid import uuid4

from typer.testing import CliRunner

from atlas_evals import __version__
from atlas_evals.cli import app
from atlas_evals.errors import AtlasApiError
from atlas_evals.models.api import (
    ChatResponse,
    ChatUsage,
    HybridSearchResponse,
    SearchResponse,
)

runner = CliRunner()

_DATASET = {
    "dataset": "cli-test",
    "version": 1,
    "questions": [
        {"id": "q1", "question": "a?", "category": "keyword",
         "expected_documents": ["gdpr.pdf"], "expected_pages": [1]},
        {"id": "u1", "question": "b?", "category": "unanswerable",
         "expected_documents": [], "expected_pages": []},
    ],
}


class _FakeClient:
    """Context-manager client that returns empty results and abstains (no errors)."""

    def __init__(self, *args: object, **kwargs: object) -> None:
        pass

    def __enter__(self) -> "_FakeClient":
        return self

    def __exit__(self, *args: object) -> None:
        return None

    def search_vector(self, query: str, **kwargs: object) -> SearchResponse:
        return SearchResponse(results=[])

    def search_keyword(self, query: str, **kwargs: object) -> SearchResponse:
        return SearchResponse(results=[])

    def search_hybrid(self, query: str, **kwargs: object) -> HybridSearchResponse:
        return HybridSearchResponse(results=[])

    def chat(self, question: str, **kwargs: object) -> ChatResponse:
        return ChatResponse(
            conversation_id=uuid4(),
            answer="x",
            citations=[],
            retrieval_mode="hybrid",
            usage=ChatUsage(),
        )


class _AlwaysFailsClient(_FakeClient):
    def search_vector(self, query: str, **kwargs: object) -> SearchResponse:
        raise AtlasApiError(500, {"error": "server_error", "message": "down"})

    def search_keyword(self, query: str, **kwargs: object) -> SearchResponse:
        raise AtlasApiError(500, {"error": "server_error", "message": "down"})

    def search_hybrid(self, query: str, **kwargs: object) -> HybridSearchResponse:
        raise AtlasApiError(500, {"error": "server_error", "message": "down"})

    def chat(self, question: str, **kwargs: object) -> ChatResponse:
        raise AtlasApiError(503, {"error": "generation_disabled", "message": "no key"})


def test_help_lists_the_version_command():
    result = runner.invoke(app, ["--help"])
    assert result.exit_code == 0
    assert "version" in result.stdout


def test_version_command_prints_the_package_version():
    result = runner.invoke(app, ["version"])
    assert result.exit_code == 0
    assert __version__ in result.stdout


def test_no_args_shows_help_and_does_not_error_hard():
    # no_args_is_help=True -> bare invocation prints help with a non-crash exit.
    result = runner.invoke(app, [])
    assert "Usage" in result.stdout


def _write_dataset(tmp_path: Path) -> Path:
    path = tmp_path / "cli-test.json"
    path.write_text(json.dumps(_DATASET), encoding="utf-8")
    return path


def test_run_writes_results_file_and_exits_zero(tmp_path, monkeypatch):
    monkeypatch.setattr("atlas_evals.cli.AtlasClient", _FakeClient)
    dataset_path = _write_dataset(tmp_path)
    out_path = tmp_path / "results.json"

    result = runner.invoke(
        app,
        ["run", "--dataset", str(dataset_path), "--out", str(out_path), "--base-url", "http://x"],
    )
    assert result.exit_code == 0, result.stdout
    assert out_path.is_file()
    data = json.loads(out_path.read_text())
    assert data["schema_version"] == 1
    assert data["dataset"] == "cli-test"
    assert data["summary"]["errored_questions"] == 0
    assert "vector" in data["summary"]["engines"]


def test_run_exits_nonzero_when_error_rate_exceeds_threshold(tmp_path, monkeypatch):
    monkeypatch.setattr("atlas_evals.cli.AtlasClient", _AlwaysFailsClient)
    dataset_path = _write_dataset(tmp_path)
    out_path = tmp_path / "results.json"

    result = runner.invoke(
        app,
        ["run", "--dataset", str(dataset_path), "--out", str(out_path), "--base-url", "http://x"],
    )
    assert result.exit_code == 1
    # The results file is still written before the non-zero exit.
    data = json.loads(out_path.read_text())
    assert data["summary"]["error_rate"] == 1.0


def test_run_rejects_unknown_engine(tmp_path, monkeypatch):
    monkeypatch.setattr("atlas_evals.cli.AtlasClient", _FakeClient)
    dataset_path = _write_dataset(tmp_path)
    result = runner.invoke(
        app, ["run", "--dataset", str(dataset_path), "--engines", "vector,bogus"]
    )
    assert result.exit_code != 0
    assert "unknown engine" in result.output
