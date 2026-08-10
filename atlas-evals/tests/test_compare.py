from pathlib import Path

import pytest

from atlas_evals.compare import _make_delta, compare_runs, render_markdown
from atlas_evals.results import RunResults

FIXTURES = Path(__file__).resolve().parent / "fixtures"


def _load(name: str) -> RunResults:
    return RunResults.model_validate_json((FIXTURES / name).read_text(encoding="utf-8"))


# --- delta math ----------------------------------------------------------------------------------


def test_higher_is_better_regression_respects_tolerance():
    # A 0.03 drop beyond the 0.02 tolerance regresses; a 0.01 drop does not.
    assert _make_delta("m", 0.90, 0.87, higher_is_better=True, gated=True, tolerance=0.02).regressed
    assert not _make_delta(
        "m", 0.90, 0.89, higher_is_better=True, gated=True, tolerance=0.02
    ).regressed


def test_lower_is_better_regression_direction():
    # false_abstention_rate is lower-is-better: a rise beyond tolerance regresses.
    up = _make_delta("m", 0.05, 0.09, higher_is_better=False, gated=True, tolerance=0.02)
    assert up.regressed and up.delta == pytest.approx(0.04)
    down = _make_delta("m", 0.09, 0.05, higher_is_better=False, gated=True, tolerance=0.02)
    assert not down.regressed


def test_ungated_metric_never_regresses():
    assert not _make_delta(
        "mean_ms", 10.0, 900.0, higher_is_better=False, gated=False, tolerance=0.02
    ).regressed


def test_missing_value_yields_no_delta():
    d = _make_delta("m", None, 0.5, higher_is_better=True, gated=True, tolerance=0.02)
    assert d.delta is None and d.regressed is False


# --- compare_runs --------------------------------------------------------------------------------


def test_regression_detected_and_snapshot_matches():
    baseline = _load("baseline_results.json")
    candidate = _load("candidate_results.json")
    report = compare_runs(baseline, candidate)

    assert report.has_gated_regression() is True
    assert "s1 · vector: page hit → miss" in report.question_regressions
    expected = (FIXTURES / "compare_snapshot.md").read_text(encoding="utf-8")
    assert render_markdown(report) == expected


def test_identical_runs_are_clean():
    baseline = _load("baseline_results.json")
    report = compare_runs(baseline, baseline)
    assert report.has_gated_regression() is False
    assert report.question_regressions == []


def test_schema_version_mismatch_flagged():
    baseline = _load("baseline_results.json")
    candidate = baseline.model_copy(update={"schema_version": 999})
    report = compare_runs(baseline, candidate)
    assert report.schema_ok is False


def test_dataset_difference_warns():
    baseline = _load("baseline_results.json")
    candidate = baseline.model_copy(update={"dataset": "some-other-dataset"})
    report = compare_runs(baseline, candidate)
    assert any("Dataset name differs" in w for w in report.warnings)


def test_compare_cli_exit_codes(tmp_path):
    from typer.testing import CliRunner

    from atlas_evals.cli import app

    runner = CliRunner()
    baseline = str(FIXTURES / "baseline_results.json")
    candidate = str(FIXTURES / "candidate_results.json")

    # Regression -> exit 1, report written.
    out = tmp_path / "c.md"
    regressed = runner.invoke(app, ["compare", baseline, candidate, "--out", str(out)])
    assert regressed.exit_code == 1
    assert "REGRESSED" in out.read_text()

    # Identical -> exit 0.
    clean = runner.invoke(app, ["compare", baseline, baseline])
    assert clean.exit_code == 0

    # A large tolerance absorbs the regression -> exit 0.
    tolerant = runner.invoke(app, ["compare", baseline, candidate, "--tolerance", "1.0"])
    assert tolerant.exit_code == 0
