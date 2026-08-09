"""Typed schema for the golden evaluation datasets (``datasets/*.json``).

Honors the Phase 2 pin that **page-level matching is mandatory**: every answerable entry must carry
``expected_pages``, and unanswerable entries must carry none. Expected pages are a single list that
mixes bare page numbers and ``{"from", "to"}`` ranges. Unknown top-level metadata keys (``about``,
``seeds_phase_4``, ``schema``, …) are ignored so the file's descriptive prose can evolve freely.
"""

from __future__ import annotations

import json
from enum import StrEnum
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field, model_validator


class Category(StrEnum):
    """What a question is meant to exercise."""

    KEYWORD = "keyword"
    SEMANTIC = "semantic"
    CROSS_DOCUMENT = "cross-document"
    HARD = "hard"
    UNANSWERABLE = "unanswerable"
    MULTI_TURN = "multi-turn"


class PageRange(BaseModel):
    """An inclusive 1-based page range, e.g. ``{"from": 55, "to": 56}``."""

    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    # "from" is a Python keyword, so the attribute is from_ with the wire alias "from".
    from_: int = Field(alias="from")
    to: int

    @model_validator(mode="after")
    def _ordered(self) -> PageRange:
        if self.to < self.from_:
            raise ValueError(f"page range end ({self.to}) is before start ({self.from_})")
        return self


# An expected-pages entry is a bare page number or a range; the list may mix both.
PageSpec = int | PageRange


class SetupTurn(BaseModel):
    """A prior user turn replayed to build conversation state before a multi-turn question."""

    model_config = ConfigDict(extra="forbid")

    question: str


class GoldenQuestion(BaseModel):
    """One evaluation question and the source(s) that should answer it."""

    model_config = ConfigDict(extra="forbid")

    id: str
    question: str
    category: Category
    # One or more expected documents (a single string is also accepted on input, see _coerce).
    expected_documents: list[str] = Field(default_factory=list)
    # Mandatory for answerable entries; mixes page numbers and ranges.
    expected_pages: list[PageSpec] = Field(default_factory=list)
    # Optional finer-grained target: specific chunk indexes known to hold the answer.
    expected_chunk_indexes: list[int] | None = None
    # Present for multi-turn entries: the turns to send before `question`.
    setup_turns: list[SetupTurn] | None = None
    notes: str = ""

    @model_validator(mode="before")
    @classmethod
    def _coerce_singular_document(cls, data: object) -> object:
        # Accept the singular `expected_document` (string or null) as well as the plural list form.
        if isinstance(data, dict) and "expected_document" in data:
            doc = data.pop("expected_document")
            data.setdefault("expected_documents", [] if doc is None else [doc])
        return data

    @model_validator(mode="after")
    def _check_page_level_matching(self) -> GoldenQuestion:
        if self.category is Category.UNANSWERABLE:
            if self.expected_documents:
                raise ValueError(f"{self.id}: unanswerable entries must have no expected_documents")
            if self.expected_pages:
                raise ValueError(f"{self.id}: unanswerable entries must have no expected_pages")
        else:
            if not self.expected_documents:
                raise ValueError(f"{self.id}: {self.category} entries require expected_documents")
            # Phase 2 pin: page-level matching is mandatory.
            if not self.expected_pages:
                raise ValueError(
                    f"{self.id}: expected_pages is required (page-level matching is mandatory)"
                )
        return self

    def expected_page_set(self) -> set[int]:
        """Flattens ``expected_pages`` (numbers and ranges) into the full set of expected pages."""
        pages: set[int] = set()
        for spec in self.expected_pages:
            if isinstance(spec, PageRange):
                pages.update(range(spec.from_, spec.to + 1))
            else:
                pages.add(spec)
        return pages


class GoldenDataset(BaseModel):
    """A golden dataset file: metadata plus the list of questions."""

    model_config = ConfigDict(extra="ignore")

    name: str = Field(alias="dataset")
    version: int
    questions: list[GoldenQuestion]

    @model_validator(mode="after")
    def _unique_ids(self) -> GoldenDataset:
        ids = [q.id for q in self.questions]
        duplicates = sorted({i for i in ids if ids.count(i) > 1})
        if duplicates:
            raise ValueError(f"{self.name}: duplicate question ids: {duplicates}")
        return self

    @classmethod
    def from_json_file(cls, path: str | Path) -> GoldenDataset:
        """Loads and validates a golden dataset from a JSON file."""
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        return cls.model_validate(data)


def load_datasets(directory: str | Path) -> list[GoldenDataset]:
    """Loads and validates every ``*.json`` golden dataset in a directory (sorted by filename)."""
    return [GoldenDataset.from_json_file(path) for path in sorted(Path(directory).glob("*.json"))]
