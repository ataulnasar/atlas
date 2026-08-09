"""``atlas-eval`` command-line entrypoint.

Run ``atlas-eval --help`` (or ``uv run atlas-eval --help``) for usage. Eval subcommands
(retrieval + generation quality against a target instance) land in later Phase 4 cards; this card
establishes the CLI, the typed models, and the API client they build on.
"""

from __future__ import annotations

import typer

from atlas_evals import __version__

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


def main() -> None:
    """Console-script entrypoint (see [project.scripts] in pyproject.toml)."""
    app()


if __name__ == "__main__":
    main()
