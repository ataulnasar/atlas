from typer.testing import CliRunner

from atlas_evals import __version__
from atlas_evals.cli import app

runner = CliRunner()


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
