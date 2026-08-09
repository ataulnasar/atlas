import uuid
from collections.abc import Callable
from typing import Any

import httpx
import pytest

from atlas_evals.client import AtlasClient
from atlas_evals.errors import AtlasApiError
from atlas_evals.models.api import DocumentStatus, SearchFilter

Handler = Callable[[httpx.Request], httpx.Response]

CHUNK_ID = "11111111-1111-1111-1111-111111111111"
DOC_ID = "22222222-2222-2222-2222-222222222222"
CONV_ID = "33333333-3333-3333-3333-333333333333"

CITATION = {
    "citationId": "c1",
    "chunkId": CHUNK_ID,
    "documentId": DOC_ID,
    "documentFilename": "gdpr.pdf",
    "documentTitle": "gdpr.pdf",
    "startPage": 55,
    "endPage": 56,
    "snippet": "The data protection officer shall …",
}


def client(handler: Handler, *, api_key: str | None = None) -> AtlasClient:
    return AtlasClient(
        base_url="http://test", api_key=api_key, transport=httpx.MockTransport(handler)
    )


def json_route(
    expected_method: str,
    expected_path: str,
    status: int,
    body: dict[str, Any] | str,
    *,
    record: list[httpx.Request] | None = None,
) -> Handler:
    """Canned-response MockTransport handler that optionally records the request."""

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == expected_method
        assert request.url.path == expected_path
        if record is not None:
            record.append(request)
        if isinstance(body, str):
            return httpx.Response(status, text=body)
        return httpx.Response(status, json=body)

    return handler


# --- happy paths ---------------------------------------------------------------------------------


def test_chat_parses_response_and_sends_camelcase_body():
    requests: list[httpx.Request] = []
    body = {
        "conversationId": CONV_ID,
        "answer": "The DPO shall … [c1]",
        "citations": [CITATION],
        "retrievalMode": "hybrid",
        "usage": {
            "promptTokens": 100,
            "completionTokens": 20,
            "totalTokens": 120,
            "model": "gpt-5-mini",
        },
    }
    handler = json_route("POST", "/api/chat", 200, body, record=requests)

    with client(handler, api_key="secret") as atlas:
        response = atlas.chat("what are the DPO tasks?", top_k=5)

    assert str(response.conversation_id) == CONV_ID
    assert response.retrieval_mode == "hybrid"
    assert response.citations[0].citation_id == "c1"
    assert response.citations[0].start_page == 55
    assert response.usage.total_tokens == 120

    sent = requests[0]
    assert sent.headers["X-API-Key"] == "secret"
    import json

    payload = json.loads(sent.content)
    assert payload == {"question": "what are the DPO tasks?", "topK": 5}  # None fields omitted


def test_search_hybrid_parses_fusion_fields():
    hit = {**CITATION, "chunkIndex": 138, "score": 0.0299, "foundBy": "both",
           "vectorRank": 4, "keywordRank": 10}
    handler = json_route("POST", "/api/search/hybrid", 200, {"results": [hit]})

    with client(handler) as atlas:
        response = atlas.search_hybrid("high-risk AI system", top_k=5)

    assert len(response.results) == 1
    result = response.results[0]
    assert result.found_by == "both"
    assert result.vector_rank == 4
    assert result.keyword_rank == 10
    assert result.chunk_index == 138
    assert result.document_filename == "gdpr.pdf"  # flat citation fields


def test_search_vector_and_null_ranks_are_allowed():
    hit = {**CITATION, "chunkIndex": 1, "score": 0.68}
    handler = json_route("POST", "/api/search/vector", 200, {"results": [hit]})
    with client(handler) as atlas:
        response = atlas.search_vector("dpo")
    assert response.results[0].score == 0.68
    assert response.results[0].citation_id == "c1"


def test_search_sends_filter_when_given():
    requests: list[httpx.Request] = []
    handler = json_route("POST", "/api/search/keyword", 200, {"results": []}, record=requests)
    with client(handler) as atlas:
        atlas.search_keyword("dpo", document_filter=SearchFilter(filenames=["gdpr.pdf"]))
    import json

    payload = json.loads(requests[0].content)
    assert payload["filter"] == {"filenames": ["gdpr.pdf"]}  # document_ids=None omitted


def test_get_chunk():
    # ChunkView has no citationId/snippet — it carries the full content instead.
    body = {
        "chunkId": CHUNK_ID,
        "documentId": DOC_ID,
        "documentFilename": "gdpr.pdf",
        "documentTitle": "gdpr.pdf",
        "chunkIndex": 117,
        "startPage": 55,
        "endPage": 56,
        "content": "full source text",
    }
    handler = json_route("GET", f"/api/chunks/{CHUNK_ID}", 200, body)
    with client(handler) as atlas:
        chunk = atlas.get_chunk(uuid.UUID(CHUNK_ID))
    assert chunk.content == "full source text"
    assert chunk.chunk_index == 117


def test_get_conversation_user_turn_has_null_citations():
    body = {
        "conversationId": CONV_ID,
        "messages": [
            {
                "role": "user",
                "content": "hi",
                "citations": None,
                "createdAt": "2026-08-09T12:00:00Z",
            },
            {
                "role": "assistant",
                "content": "hello [c1]",
                "citations": [CITATION],
                "createdAt": "2026-08-09T12:00:01Z",
            },
        ],
    }
    handler = json_route("GET", f"/api/conversations/{CONV_ID}", 200, body)
    with client(handler) as atlas:
        conversation = atlas.get_conversation(uuid.UUID(CONV_ID))
    assert conversation.messages[0].role == "user"
    assert conversation.messages[0].citations is None
    assistant_citations = conversation.messages[1].citations
    assert assistant_citations is not None
    assert assistant_citations[0].citation_id == "c1"


def test_get_document_parses_status_enum():
    body = {
        "id": DOC_ID,
        "filename": "gdpr.pdf",
        "status": "READY",
        "errorMessage": None,
        "chunkCount": 42,
    }
    handler = json_route("GET", f"/api/documents/{DOC_ID}", 200, body)
    with client(handler) as atlas:
        document = atlas.get_document(uuid.UUID(DOC_ID))
    assert document.status is DocumentStatus.READY
    assert document.chunk_count == 42
    assert document.error_message is None


# --- auth header ---------------------------------------------------------------------------------


def test_no_api_key_sends_no_header(monkeypatch):
    monkeypatch.delenv("ATLAS_API_KEY", raising=False)
    requests: list[httpx.Request] = []
    handler = json_route("POST", "/api/search/vector", 200, {"results": []}, record=requests)
    with client(handler) as atlas:
        atlas.search_vector("dpo")
    assert "X-API-Key" not in requests[0].headers


def test_api_key_from_env_is_sent(monkeypatch):
    monkeypatch.setenv("ATLAS_API_KEY", "env-key")
    requests: list[httpx.Request] = []
    handler = json_route("POST", "/api/search/vector", 200, {"results": []}, record=requests)
    # No explicit api_key -> falls back to the env var.
    atlas = AtlasClient(base_url="http://test", transport=httpx.MockTransport(handler))
    with atlas:
        atlas.search_vector("dpo")
    assert requests[0].headers["X-API-Key"] == "env-key"


# --- error mapping -------------------------------------------------------------------------------


@pytest.mark.parametrize("status", [401, 404, 422])
def test_non_2xx_raises_atlas_api_error_with_status_and_body(status):
    body = {"error": "some_error", "message": "why it failed"}
    handler = json_route("GET", f"/api/documents/{DOC_ID}", status, body)
    with client(handler, api_key="k") as atlas:
        with pytest.raises(AtlasApiError) as excinfo:
            atlas.get_document(uuid.UUID(DOC_ID))
    error = excinfo.value
    assert error.status_code == status
    assert error.body == body
    assert "why it failed" in str(error)


def test_non_json_error_body_is_kept_as_text():
    handler = json_route("GET", f"/api/documents/{DOC_ID}", 500, "upstream boom")
    with client(handler) as atlas:
        with pytest.raises(AtlasApiError) as excinfo:
            atlas.get_document(uuid.UUID(DOC_ID))
    assert excinfo.value.status_code == 500
    assert excinfo.value.body == "upstream boom"
