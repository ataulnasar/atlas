import sys

import pytest

from evals import run


def test_main_requires_target_argument(monkeypatch):
    monkeypatch.setattr(sys, "argv", ["run.py"])

    with pytest.raises(SystemExit):
        run.main()


def test_main_raises_not_implemented_once_target_is_provided(monkeypatch):
    monkeypatch.setattr(sys, "argv", ["run.py", "--target", "http://localhost:8080"])

    with pytest.raises(NotImplementedError):
        run.main()
