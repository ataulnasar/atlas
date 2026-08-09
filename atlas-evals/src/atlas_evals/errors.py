"""Typed error for Atlas API failures."""

from __future__ import annotations

from typing import Any


class AtlasApiError(Exception):
    """Raised when the Atlas API returns a non-2xx response.

    Carries the HTTP ``status_code`` and the response ``body`` (the parsed JSON — typically an
    ``{error, message}`` object — or the raw text when the body isn't JSON).
    """

    def __init__(self, status_code: int, body: Any) -> None:
        self.status_code = status_code
        self.body = body
        detail = ""
        if isinstance(body, dict):
            error = body.get("error")
            message = body.get("message")
            if error or message:
                detail = f" ({error}: {message})"
        super().__init__(f"Atlas API request failed with status {status_code}{detail}")
