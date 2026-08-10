from pathlib import Path
from uuid import UUID

import httpx
import pytest
from typer.testing import CliRunner

from atlas_evals.cli import app
from atlas_evals.doctor import (
    Status,
    check_atlas_api_key,
    check_auth,
    check_corpus,
    check_generation,
    check_health,
    check_openai_api_key,
    has_failure,
    render,
    run_checks,
)
from atlas_evals.errors import AtlasApiError
from atlas_evals.models.api import (
    AdminStatsResponse,
    ChatResponse,
    ChatUsage,
    HealthResponse,
    SearchResponse,
)
from atlas_evals.models.golden import GoldenDataset
from atlas_evals.testing import mock_atlas

DATASET_PATH = Path(__file__).resolve().parent.parent / "datasets" / "ci-smoke.json"
_ZERO = UUID(int=0)


# --- pure env detectors --------------------------------------------------------------------------


def test_atlas_key_unset_warns():
    for raw in (None, ""):
        assert check_atlas_api_key(raw).status is Status.WARN


def test_atlas_key_whitespace_fails():
    for raw in (" key", "key\n", "\tkey "):
        check = check_atlas_api_key(raw)
        assert check.status is Status.FAIL
        assert "whitespace" in check.detail


def test_atlas_key_shaped_like_openai_key_warns():
    # The exact confusion that happened in this project: an sk- key in ATLAS_API_KEY.
    check = check_atlas_api_key("sk-fake")
    assert check.status is Status.WARN
    assert "sk-" in check.detail


def test_atlas_key_clean_passes():
    assert check_atlas_api_key("atlas-door-key").status is Status.PASS


def test_openai_key_unset_warns():
    assert check_openai_api_key(None).status is Status.WARN


def test_openai_key_whitespace_fails():
    assert check_openai_api_key("sk-fake\n").status is Status.FAIL


def test_openai_key_without_sk_prefix_warns():
    assert check_openai_api_key("not-an-openai-key").status is Status.WARN


def test_openai_key_clean_passes():
    assert check_openai_api_key("sk-fake").status is Status.PASS


def test_no_secret_value_is_ever_echoed():
    secret = "sk-super-secret-value-1234567890"  # gitleaks:allow (fake, for the leak assertion)
    for check in (check_atlas_api_key(secret), check_openai_api_key(secret)):
        assert secret not in check.detail
        assert secret not in check.hint


# --- network checks via fakes --------------------------------------------------------------------


class _HealthyClient:
    def __enter__(self) -> "_HealthyClient":
        return self

    def __exit__(self, *args: object) -> None:
        return None

    def health(self) -> HealthResponse:
        return HealthResponse(status="UP")

    def search_keyword(self, query: str, *, top_k: int | None = None) -> SearchResponse:
        return SearchResponse(results=[])

    def get_admin_stats(self) -> AdminStatsResponse:
        return AdminStatsResponse(
            total_documents=5, ready_documents=5, total_chunks=50, chunks_without_embedding=0
        )

    def chat(
        self, question: str, *, conversation_id: UUID | None = None, top_k: int | None = None
    ) -> ChatResponse:
        return ChatResponse(
            conversation_id=_ZERO,
            answer="pong",
            citations=[],
            retrieval_mode="vector",
            usage=ChatUsage(),
        )


def test_healthy_client_all_pass():
    client = _HealthyClient()
    checks = [
        check_health(client),
        check_auth(client),
        check_corpus(client),
        check_generation(client),
    ]
    assert all(c.status is Status.PASS for c in checks)


def test_auth_401_fails_with_key_mismatch_hint():
    class C(_HealthyClient):
        def search_keyword(self, query: str, *, top_k: int | None = None) -> SearchResponse:
            raise AtlasApiError(401, {"error": "unauthorized", "message": "bad key"})

    check = check_auth(C())
    assert check.status is Status.FAIL
    assert "401" in check.detail
    assert "force-recreate" in check.hint


def test_health_connection_error_fails():
    class C(_HealthyClient):
        def health(self) -> HealthResponse:
            raise httpx.ConnectError("connection refused")

    check = check_health(C())
    assert check.status is Status.FAIL
    assert "connect" in check.detail.lower()


def test_generation_disabled_warns_not_fails():
    class C(_HealthyClient):
        def chat(
            self, question: str, *, conversation_id: UUID | None = None, top_k: int | None = None
        ) -> ChatResponse:
            raise AtlasApiError(503, {"error": "generation_disabled", "message": "no key"})

    check = check_generation(C())
    assert check.status is Status.WARN
    assert "disabled" in check.detail


def test_generation_provider_5xx_warns():
    class C(_HealthyClient):
        def chat(
            self, question: str, *, conversation_id: UUID | None = None, top_k: int | None = None
        ) -> ChatResponse:
            raise AtlasApiError(502, {"error": "generation_failed", "message": "upstream"})

    assert check_generation(C()).status is Status.WARN


def test_corpus_empty_warns():
    class C(_HealthyClient):
        def get_admin_stats(self) -> AdminStatsResponse:
            return AdminStatsResponse(
                total_documents=0, ready_documents=0, total_chunks=0, chunks_without_embedding=0
            )

    assert check_corpus(C()).status is Status.WARN


def test_corpus_unembedded_warns():
    class C(_HealthyClient):
        def get_admin_stats(self) -> AdminStatsResponse:
            return AdminStatsResponse(
                total_documents=2, ready_documents=2, total_chunks=20, chunks_without_embedding=7
            )

    check = check_corpus(C())
    assert check.status is Status.WARN
    assert "unembedded" in check.detail


def test_run_checks_and_has_failure():
    checks = run_checks(_HealthyClient(), atlas_api_key="door", openai_api_key="sk-fake")
    assert not has_failure(checks)
    checks = run_checks(_HealthyClient(), atlas_api_key=" bad ", openai_api_key=None)
    assert has_failure(checks)  # whitespace in ATLAS_API_KEY -> FAIL


def test_render_fix_hints_toggles_hint_lines():
    checks = run_checks(_HealthyClient(), atlas_api_key=" bad ", openai_api_key=None)
    without = render(checks, fix_hints=False)
    with_hints = render(checks, fix_hints=True)
    assert not any("hint:" in line for line in without)
    assert any("hint:" in line for line in with_hints)


# --- end-to-end against the mock Atlas -----------------------------------------------------------


def test_doctor_command_passes_against_mock(monkeypatch):
    monkeypatch.setenv("ATLAS_API_KEY", "atlas-door-key")
    monkeypatch.setenv("SPRING_AI_OPENAI_API_KEY", "sk-fake")  # gitleaks:allow (fake test value)
    dataset = GoldenDataset.from_json_file(DATASET_PATH)
    runner = CliRunner()
    with mock_atlas(dataset) as base_url:
        result = runner.invoke(app, ["doctor", "--base-url", base_url])
    assert result.exit_code == 0, result.output
    assert "FAIL" not in result.output
    assert "6 passed" in result.output


def test_doctor_command_exits_nonzero_on_failure(monkeypatch):
    # Point at an unroutable/closed port so every network check fails -> exit 1.
    monkeypatch.setenv("ATLAS_API_KEY", "atlas-door-key")
    monkeypatch.setenv("SPRING_AI_OPENAI_API_KEY", "sk-fake")  # gitleaks:allow (fake test value)
    runner = CliRunner()
    result = runner.invoke(app, ["doctor", "--base-url", "http://127.0.0.1:1"])
    assert result.exit_code == 1
    assert "FAIL" in result.output


@pytest.mark.parametrize("flag", [[], ["--fix-hints"]])
def test_doctor_help_lists_command(flag):
    result = CliRunner().invoke(app, ["doctor", *flag, "--help"])
    assert result.exit_code == 0
