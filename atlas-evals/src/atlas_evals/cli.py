"""``atlas-eval`` command-line entrypoint.

Run ``atlas-eval --help`` (or ``uv run atlas-eval --help``) for usage.
"""

from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path

import typer

from atlas_evals import __version__
from atlas_evals.client import AtlasClient
from atlas_evals.models.golden import GoldenDataset
from atlas_evals.report import render_html, render_markdown
from atlas_evals.results import RunResults
from atlas_evals.runner import ALL_ENGINES, ERROR_EXIT_THRESHOLD, run_dataset

app = typer.Typer(
    help="Evaluate atlas-core's retrieval and generation quality against a running instance.",
    no_args_is_help=True,
    add_completion=False,
)


@app.callback()
def root() -> None:
    """Force group (multi-command) mode so subcommands aren't collapsed into the root."""


@app.command()
def version() -> None:
    """Print the atlas-evals version."""
    typer.echo(__version__)


def _resolve_dataset(dataset: str) -> Path:
    """Resolves --dataset as a path, else a name under datasets/ (with or without .json)."""
    candidates = [
        Path(dataset),
        Path("datasets") / dataset,
        Path("datasets") / f"{dataset}.json",
    ]
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    raise typer.BadParameter(
        f"dataset not found: '{dataset}' (looked for a path, and datasets/{dataset}[.json])"
    )


def _parse_engines(engines: str) -> list[str]:
    selected = [e.strip() for e in engines.split(",") if e.strip()]
    unknown = [e for e in selected if e not in ALL_ENGINES]
    if unknown:
        raise typer.BadParameter(f"unknown engine(s): {unknown}; choose from {list(ALL_ENGINES)}")
    if not selected:
        raise typer.BadParameter("no engines selected")
    return selected


@app.command()
def run(
    dataset: str = typer.Option(
        ..., "--dataset", "-d", help="Path to a dataset JSON, or a name under datasets/."
    ),
    base_url: str = typer.Option(
        "http://localhost:8080", "--base-url", help="Base URL of the Atlas instance."
    ),
    engines: str = typer.Option(
        ",".join(ALL_ENGINES),
        "--engines",
        help="Comma-separated: vector,keyword,hybrid,chat.",
    ),
    top_k: int = typer.Option(5, "--top-k", "-k", help="Top-K for retrieval hit@K."),
    timeout: float = typer.Option(60.0, "--timeout", help="Per-request timeout (seconds)."),
    out: Path | None = typer.Option(
        None, "--out", "-o", help="Results JSON path (default results/run-TIMESTAMP.json)."
    ),
) -> None:
    """Run a golden dataset against a live Atlas and write a results JSON.

    Reads the API key from ATLAS_API_KEY when set. Exits non-zero only if more than
    20% of questions errored.
    """
    dataset_path = _resolve_dataset(dataset)
    selected_engines = _parse_engines(engines)
    golden = GoldenDataset.from_json_file(dataset_path)

    out_path = out or Path("results") / f"run-{datetime.now(UTC):%Y%m%dT%H%M%SZ}.json"

    typer.echo(f"Running {golden.name} ({len(golden.questions)} questions) against {base_url}")
    typer.echo(f"Engines: {', '.join(selected_engines)}  top_k={top_k}")

    with AtlasClient(base_url=base_url, timeout=timeout) as client:
        results = run_dataset(
            client,
            golden,
            base_url=base_url,
            engines=selected_engines,
            top_k=top_k,
            progress=lambda line: typer.echo(line),
        )

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(results.model_dump_json(indent=2), encoding="utf-8")

    summary = results.summary
    typer.echo("")
    typer.echo(f"Wrote {out_path}")
    for engine, engine_summary in summary.engines.items():
        typer.echo(
            f"  {engine:<8} doc_hit={engine_summary.document_hit_rate:.2f} "
            f"page_hit={engine_summary.page_hit_rate:.2f} mrr={engine_summary.mrr:.3f} "
            f"(n={engine_summary.evaluated})"
        )
    if summary.chat is not None:
        chat = summary.chat
        typer.echo(
            f"  chat     cite_doc_acc={chat.citation_document_accuracy:.2f} "
            f"cite_page_hit={chat.citation_page_hit_rate:.2f} "
            f"false_abstain={chat.false_abstention_rate:.2f} "
            f"abstain(unans)={chat.abstention_rate:.2f}"
        )
    typer.echo(
        f"  errors: {summary.errored_questions}/{summary.total_questions} "
        f"({summary.error_rate:.0%})"
    )

    if summary.error_rate > ERROR_EXIT_THRESHOLD:
        typer.echo(
            f"error rate {summary.error_rate:.0%} exceeds "
            f"{ERROR_EXIT_THRESHOLD:.0%} threshold",
            err=True,
        )
        raise typer.Exit(code=1)


@app.command()
def report(
    results_file: Path = typer.Argument(..., help="Path to a run results JSON file."),
    out: Path | None = typer.Option(None, "--out", "-o", help="Write to this file (else stdout)."),
    as_html: bool = typer.Option(False, "--html", help="Render HTML instead of Markdown."),
) -> None:
    """Render a Markdown (or --html) report from a results file."""
    results = RunResults.model_validate_json(results_file.read_text(encoding="utf-8"))
    content = render_html(results) if as_html else render_markdown(results)
    if out is None:
        typer.echo(content)
    else:
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(content, encoding="utf-8")
        typer.echo(f"Wrote {out}")


def main() -> None:
    """Console-script entrypoint (see [project.scripts] in pyproject.toml)."""
    app()


if __name__ == "__main__":
    main()
