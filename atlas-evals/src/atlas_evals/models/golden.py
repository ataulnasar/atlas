"""Typed model of the golden evaluation dataset (``datasets/*.json``).

Mirrors the schema documented in ``datasets/mini-golden.json``. Unknown top-level metadata keys
(``about``, ``seeds_phase_4``, ``schema``, …) are ignored so the descriptive prose in the file can
evolve without breaking loading.
"""

from __future__ import annotations

import json
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field


class ExpectedPageRange(BaseModel):
    """An inclusive 1-based page range, e.g. ``{"from": 55, "to": 56}``."""

    model_config = ConfigDict(populate_by_name=True)

    # "from" is a Python keyword, so the attribute is from_ with the wire alias "from".
    from_: int = Field(alias="from")
    to: int


# expected_pages is a range, an explicit list of pages, or null (unanswerable).
ExpectedPages = ExpectedPageRange | list[int] | None


class GoldenQuestion(BaseModel):
    """One evaluation question and its expected source(s)."""

    model_config = ConfigDict(extra="ignore")

    id: str
    question: str
    expected_document: str | None = None
    expected_pages: ExpectedPages = None
    category: str
    notes: str = ""


class GoldenDataset(BaseModel):
    """A golden dataset file: metadata plus the list of questions."""

    model_config = ConfigDict(extra="ignore")

    name: str = Field(alias="dataset")
    version: int
    questions: list[GoldenQuestion]

    @classmethod
    def from_json_file(cls, path: str | Path) -> GoldenDataset:
        """Loads and validates a golden dataset from a JSON file."""
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        return cls.model_validate(data)
