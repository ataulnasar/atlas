#!/usr/bin/env python
"""CI harness smoke: run the full eval pipeline against an in-process mock Atlas.

No OpenAI key, no running stack, no Docker. Proves run -> results schema -> report -> compare
end-to-end and asserts exit codes. Wired as a step in the python CI lane; also runnable locally
via `make smoke`.
"""

from __future__ import annotations

import sys
import tempfile
from pathlib import Path

from typer.testing import CliRunner

from atlas_evals.cli import app
from atlas_evals.models.golden import GoldenDataset
from atlas_evals.testing import mock_atlas

DATASET_PATH = Path("datasets/ci-smoke.json")
BASELINE_PATH = Path("tests/fixtures/ci_smoke_baseline.json")


def run_smoke() -> None:
    dataset = GoldenDataset.from_json_file(DATASET_PATH)
    runner = CliRunner()
    with mock_atlas(dataset) as base_url, tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp) / "smoke-results.json"

        run_result = runner.invoke(
            app,
            ["run", "--dataset", str(DATASET_PATH), "--base-url", base_url, "--out", str(out)],
        )
        assert run_result.exit_code == 0, f"run failed:\n{run_result.output}"
        assert out.is_file(), "run did not write a results file"

        report_result = runner.invoke(app, ["report", str(out)])
        assert report_result.exit_code == 0, f"report failed:\n{report_result.output}"
        assert "Atlas evaluation report" in report_result.output

        # The mock is deterministic, so a fresh run must compare clean against the committed
        # baseline (exit 0). This is the gate that would catch a pipeline/schema regression.
        compare_result = runner.invoke(app, ["compare", str(BASELINE_PATH), str(out)])
        assert compare_result.exit_code == 0, (
            f"compare against baseline regressed:\n{compare_result.output}"
        )


def main() -> int:
    try:
        run_smoke()
    except AssertionError as failure:
        print(f"CI smoke eval: FAIL\n{failure}", file=sys.stderr)
        return 1
    print("CI smoke eval: PASS (run -> report -> compare, mock Atlas)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
