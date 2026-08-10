"""Diagnostics for an Atlas deployment — the engine behind ``atlas-eval doctor``.

Each check returns a :class:`Check` with a PASS/WARN/FAIL verdict and a remediation hint. Checks
cover the things that actually break a deployment in practice: env-var mistakes (the ATLAS_API_KEY /
OpenAI-key confusion, stray whitespace), reachability, an authenticated round-trip (so a key
mismatch reads as 401, not a vague failure), corpus state, and whether generation is wired up.

Secrets are never printed — only presence, shape, and whitespace verdicts. The detectors are pure
functions so they can be unit-tested directly; the network checks take a small client protocol so
they can be driven by the mock Atlas or a fake.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from typing import Protocol
from uuid import UUID

import httpx

from atlas_evals.errors import AtlasApiError
from atlas_evals.models.api import (
    AdminStatsResponse,
    ChatResponse,
    HealthResponse,
    SearchResponse,
)

OPENAI_KEY_PREFIX = "sk-"
_PROBE_QUERY = "atlas doctor connectivity probe"
_PROBE_QUESTION = "Reply with the single word: pong."


class Status(StrEnum):
    PASS = "PASS"
    WARN = "WARN"
    FAIL = "FAIL"


@dataclass(frozen=True)
class Check:
    """One diagnostic result: a named verdict, a one-line detail, and a remediation hint."""

    name: str
    status: Status
    detail: str
    hint: str = ""


class DoctorClient(Protocol):
    """The subset of :class:`~atlas_evals.client.AtlasClient` the network checks need."""

    def health(self) -> HealthResponse: ...
    def search_keyword(self, query: str, *, top_k: int | None = None) -> SearchResponse: ...
    def get_admin_stats(self) -> AdminStatsResponse: ...
    def chat(
        self, question: str, *, conversation_id: UUID | None = None, top_k: int | None = None
    ) -> ChatResponse: ...


# --- pure detectors ------------------------------------------------------------------------------


def _has_edge_whitespace(value: str) -> bool:
    """True if the value has leading/trailing whitespace (spaces, tabs, newlines)."""
    return value != value.strip()


def _looks_like_openai_key(value: str) -> bool:
    """True if the value looks like an OpenAI key (``sk-`` prefix), ignoring edge whitespace."""
    return value.strip().startswith(OPENAI_KEY_PREFIX)


def check_atlas_api_key(raw: str | None) -> Check:
    """ATLAS_API_KEY — Atlas's own door key (``X-API-Key``), not the OpenAI credential."""
    name = "env: ATLAS_API_KEY"
    if not raw:
        return Check(
            name,
            Status.WARN,
            "not set — the server runs keyless (auth disabled). Fine for dev/CI; set it in prod.",
            "Set ATLAS_API_KEY to the same value the server uses.",
        )
    if _has_edge_whitespace(raw):
        return Check(
            name,
            Status.FAIL,
            "has leading/trailing whitespace — a classic 401 cause (the compare is byte-exact).",
            "Re-set it without surrounding spaces/newlines; source it from the file, don't retype.",
        )
    if _looks_like_openai_key(raw):
        return Check(
            name,
            Status.WARN,
            "starts with 'sk-' — that looks like an OpenAI key, not Atlas's own door key.",
            "Check you didn't swap ATLAS_API_KEY and SPRING_AI_OPENAI_API_KEY.",
        )
    return Check(name, Status.PASS, f"set ({len(raw)} chars), no surrounding whitespace.")


def check_openai_api_key(raw: str | None) -> Check:
    """SPRING_AI_OPENAI_API_KEY — the OpenAI credential used for embeddings and generation."""
    name = "env: SPRING_AI_OPENAI_API_KEY"
    if not raw:
        return Check(
            name,
            Status.WARN,
            "not set — generation (chat) is disabled server-side; retrieval still works.",
            "Set it to your OpenAI key to enable answering.",
        )
    if _has_edge_whitespace(raw):
        return Check(
            name,
            Status.FAIL,
            "has leading/trailing whitespace — the provider will reject it.",
            "Re-set it without surrounding spaces/newlines.",
        )
    if not _looks_like_openai_key(raw):
        return Check(
            name,
            Status.WARN,
            "set, but does not start with 'sk-' — may not be a valid OpenAI key.",
            "Confirm this is the OpenAI credential (OpenAI keys start with 'sk-').",
        )
    return Check(name, Status.PASS, f"set ({len(raw)} chars), looks like an OpenAI key.")


# --- network checks ------------------------------------------------------------------------------


def check_health(client: DoctorClient) -> Check:
    name = "server reachability"
    try:
        health = client.health()
    except AtlasApiError as err:
        return Check(
            name,
            Status.FAIL,
            f"health endpoint returned HTTP {err.status_code}.",
            "The app is up but unhealthy — check its logs (database connectivity?).",
        )
    except httpx.RequestError as err:
        return Check(
            name,
            Status.FAIL,
            f"could not connect ({type(err).__name__}).",
            "Is the stack up and --base-url correct? Check `docker compose ps` and ATLAS_UI_PORT.",
        )
    if health.status.upper() == "UP":
        return Check(name, Status.PASS, "reachable; /actuator/health reports UP.")
    return Check(
        name,
        Status.WARN,
        f"reachable but health status is {health.status!r}.",
        "Check the app logs for the failing health component.",
    )


def check_auth(client: DoctorClient) -> Check:
    name = "authenticated request"
    try:
        client.search_keyword(_PROBE_QUERY, top_k=1)
    except AtlasApiError as err:
        if err.status_code == 401:
            return Check(
                name,
                Status.FAIL,
                "401 Unauthorized — the client's key doesn't match the server's.",
                "Source ATLAS_API_KEY from the same file the server uses (don't retype), then "
                "force-recreate the service.",
            )
        if err.status_code >= 500:
            return Check(
                name,
                Status.FAIL,
                f"server error (HTTP {err.status_code}) on a search request.",
                "Check the atlas-core logs.",
            )
        return Check(name, Status.WARN, f"unexpected HTTP {err.status_code} on a search request.")
    except httpx.RequestError as err:
        return Check(
            name,
            Status.FAIL,
            f"could not connect ({type(err).__name__}).",
            "Is the stack up and --base-url correct?",
        )
    return Check(name, Status.PASS, "keyword search returned 200 (auth OK).")


def check_corpus(client: DoctorClient) -> Check:
    name = "corpus state"
    try:
        stats = client.get_admin_stats()
    except AtlasApiError as err:
        if err.status_code == 401:
            return Check(
                name,
                Status.FAIL,
                "401 Unauthorized fetching stats.",
                "See the authenticated-request check.",
            )
        if err.status_code == 404:
            return Check(
                name,
                Status.WARN,
                "no /api/admin/stats endpoint (older atlas-core?).",
                "Upgrade atlas-core to a build that includes the stats endpoint.",
            )
        return Check(name, Status.FAIL, f"HTTP {err.status_code} fetching stats.")
    except httpx.RequestError as err:
        return Check(name, Status.FAIL, f"could not connect ({type(err).__name__}).")

    detail = (
        f"{stats.total_documents} documents ({stats.ready_documents} READY), "
        f"{stats.total_chunks} chunks, {stats.chunks_without_embedding} without embeddings."
    )
    if stats.total_documents == 0:
        return Check(
            name, Status.WARN, f"{detail} No documents ingested yet.", "Ingest a corpus (runbook)."
        )
    if stats.chunks_without_embedding > 0:
        return Check(
            name,
            Status.WARN,
            f"{detail} Some chunks are unembedded.",
            "Run the embedding backfill (POST /api/admin/embeddings/backfill), or wait it out.",
        )
    return Check(name, Status.PASS, detail)


def check_generation(client: DoctorClient) -> Check:
    name = "generation (chat)"
    try:
        client.chat(_PROBE_QUESTION)
    except AtlasApiError as err:
        if err.status_code == 503:
            return Check(
                name,
                Status.WARN,
                "disabled server-side (no OpenAI key configured).",
                "Set SPRING_AI_OPENAI_API_KEY in the server's environment to enable answering.",
            )
        if err.status_code == 401:
            return Check(
                name,
                Status.FAIL,
                "401 Unauthorized on chat.",
                "See the authenticated-request check.",
            )
        if err.status_code >= 500:
            return Check(
                name,
                Status.WARN,
                f"provider/generation error (HTTP {err.status_code}).",
                "Check the atlas-core logs and the OpenAI key/quota.",
            )
        return Check(name, Status.WARN, f"unexpected HTTP {err.status_code} on chat.")
    except httpx.RequestError as err:
        return Check(name, Status.FAIL, f"could not connect ({type(err).__name__}).")
    return Check(name, Status.PASS, "chat returned an answer.")


# --- orchestration -------------------------------------------------------------------------------


def run_checks(
    client: DoctorClient, *, atlas_api_key: str | None, openai_api_key: str | None
) -> list[Check]:
    """Run every diagnostic and return the results in report order."""
    return [
        check_atlas_api_key(atlas_api_key),
        check_openai_api_key(openai_api_key),
        check_health(client),
        check_auth(client),
        check_corpus(client),
        check_generation(client),
    ]


def has_failure(checks: list[Check]) -> bool:
    return any(check.status is Status.FAIL for check in checks)


def render(checks: list[Check], *, fix_hints: bool) -> list[str]:
    """Format checks as report lines; with ``fix_hints``, add a hint line under each check."""
    lines: list[str] = []
    for check in checks:
        lines.append(f"[{check.status.value:>4}] {check.name}: {check.detail}")
        if fix_hints and check.hint:
            lines.append(f"       hint: {check.hint}")
    return lines
