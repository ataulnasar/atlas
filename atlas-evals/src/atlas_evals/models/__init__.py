"""Pydantic v2 models for atlas-evals.

- :mod:`atlas_evals.models.golden` — the golden evaluation dataset schema.
- :mod:`atlas_evals.models.api` — response/request models mirroring atlas-core's HTTP contract
  (added with the API client).
"""

from atlas_evals.models.golden import (
    ExpectedPageRange,
    GoldenDataset,
    GoldenQuestion,
)

__all__ = [
    "ExpectedPageRange",
    "GoldenDataset",
    "GoldenQuestion",
]
