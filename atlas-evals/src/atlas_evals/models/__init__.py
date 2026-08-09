"""Pydantic v2 models for atlas-evals.

- :mod:`atlas_evals.models.golden` — the golden evaluation dataset schema.
- :mod:`atlas_evals.models.api` — response/request models mirroring atlas-core's HTTP contract.
"""

from atlas_evals.models.api import (
    ApiErrorBody,
    ChatRequest,
    ChatResponse,
    ChatUsage,
    ChunkView,
    Citation,
    ConversationMessageView,
    ConversationResponse,
    DocumentStatus,
    DocumentStatusResponse,
    HybridSearchHit,
    HybridSearchResponse,
    SearchFilter,
    SearchHit,
    SearchRequest,
    SearchResponse,
)
from atlas_evals.models.golden import (
    Category,
    GoldenDataset,
    GoldenQuestion,
    PageRange,
    SetupTurn,
    load_datasets,
)

__all__ = [
    # golden dataset
    "Category",
    "GoldenDataset",
    "GoldenQuestion",
    "PageRange",
    "SetupTurn",
    "load_datasets",
    # api contract
    "ApiErrorBody",
    "ChatRequest",
    "ChatResponse",
    "ChatUsage",
    "ChunkView",
    "Citation",
    "ConversationMessageView",
    "ConversationResponse",
    "DocumentStatus",
    "DocumentStatusResponse",
    "HybridSearchHit",
    "HybridSearchResponse",
    "SearchFilter",
    "SearchHit",
    "SearchRequest",
    "SearchResponse",
]
