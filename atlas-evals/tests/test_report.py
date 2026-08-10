from pathlib import Path

from atlas_evals.report import render_html, render_markdown
from atlas_evals.results import RunResults

FIXTURES = Path(__file__).resolve().parent / "fixtures"


def _baseline() -> RunResults:
    return RunResults.model_validate_json(
        (FIXTURES / "baseline_results.json").read_text(encoding="utf-8")
    )


def test_markdown_matches_snapshot():
    expected = (FIXTURES / "report_snapshot.md").read_text(encoding="utf-8")
    assert render_markdown(_baseline()) == expected


def test_report_has_all_required_sections():
    md = render_markdown(_baseline())
    for heading in [
        "# Atlas evaluation report",
        "## Retrieval summary",
        "## Chat summary",
        "## Per-category breakdown",
        "## Failure evidence: citation-document misses",
        "## Per-question detail",
        "## Denominators & notes",
    ]:
        assert heading in md


def test_failure_evidence_lists_unexpected_documents():
    md = render_markdown(_baseline())
    # x1 cited nis1.pdf (unexpected) alongside the expected nis2.pdf.
    assert "nis1.pdf" in md
    assert "strict subset check" in md  # the always-printed adjudication note


def test_denominators_and_cost_are_reported():
    md = render_markdown(_baseline())
    assert "Retrieval n = 3" in md  # 4 questions, 1 unanswerable skips retrieval
    assert "estimated cost" in md
    assert "$" in md  # gpt-5-mini pricing derivable


def test_html_render_is_self_contained():
    html_out = render_html(_baseline())
    assert html_out.startswith("<!doctype html>")
    assert "<style>" in html_out and "<table>" in html_out
    assert "fixture-golden" in html_out
    # no JS frameworks / external assets (CSS is inlined)
    assert "<script" not in html_out
    assert "cdn" not in html_out.lower()
    assert "stylesheet" not in html_out.lower()


def test_report_cli_writes_markdown_and_html(tmp_path):
    from typer.testing import CliRunner

    from atlas_evals.cli import app

    runner = CliRunner()
    md_out = tmp_path / "r.md"
    result = runner.invoke(
        app, ["report", str(FIXTURES / "baseline_results.json"), "--out", str(md_out)]
    )
    assert result.exit_code == 0, result.output
    assert "# Atlas evaluation report" in md_out.read_text()

    html_out = tmp_path / "r.html"
    result = runner.invoke(
        app,
        ["report", str(FIXTURES / "baseline_results.json"), "--out", str(html_out), "--html"],
    )
    assert result.exit_code == 0
    assert html_out.read_text().startswith("<!doctype html>")
