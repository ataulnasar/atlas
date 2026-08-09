import type { ApiError, ChatRequestBody, ChatUsage, ChunkView, Citation } from "./types";
import type { ChatEvent } from "../state/chat";
import { createSseDecoder, type SseEvent } from "./sse";
import { authHeaders } from "./apiKey";

export interface StreamHandlers {
  /** A decoded stream event (token / citations / done / error). */
  onEvent(event: ChatEvent): void;
  /** A pre-stream HTTP failure (400 blank question, 503 no generator) — the body is an ApiError. */
  onPreStreamError(status: number, error: ApiError): void;
  /** The request never reached the server, or the connection dropped. */
  onNetworkError(error: unknown): void;
}

/** Turns a raw SSE event into a typed ChatEvent; null for anything unrecognized/malformed. */
function toChatEvent(raw: SseEvent): ChatEvent | null {
  let data: Record<string, unknown>;
  try {
    data = JSON.parse(raw.data) as Record<string, unknown>;
  } catch {
    return null;
  }
  switch (raw.event) {
    case "token":
      return { type: "token", delta: String(data.delta ?? "") };
    case "citations":
      return {
        type: "citations",
        answer: String(data.answer ?? ""),
        citations: Array.isArray(data.citations) ? (data.citations as Citation[]) : [],
      };
    case "done":
      return {
        type: "done",
        conversationId: String(data.conversationId ?? ""),
        retrievalMode: String(data.retrievalMode ?? ""),
        usage: (data.usage as ChatUsage | undefined) ?? {
          promptTokens: null,
          completionTokens: null,
          totalTokens: null,
          model: null,
        },
      };
    case "error":
      return {
        type: "error",
        code: String(data.error ?? "error"),
        message: String(data.message ?? ""),
      };
    default:
      return null;
  }
}

/**
 * POSTs a chat turn and streams the response. Consumes text/event-stream from the fetch body
 * reader (EventSource can't POST), decoding across chunk boundaries. Resolves when the stream ends.
 */
export async function streamChat(
  body: ChatRequestBody,
  handlers: StreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  let response: Response;
  try {
    response = await fetch("/api/chat/stream", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Accept: "text/event-stream",
        ...authHeaders(),
      },
      body: JSON.stringify(body),
      signal,
    });
  } catch (error) {
    handlers.onNetworkError(error);
    return;
  }

  if (!response.ok) {
    let error: ApiError = { error: "request_failed", message: `Request failed (${response.status})` };
    try {
      error = (await response.json()) as ApiError;
    } catch {
      // keep the fallback message
    }
    handlers.onPreStreamError(response.status, error);
    return;
  }

  if (!response.body) {
    handlers.onNetworkError(new Error("Response had no body to stream"));
    return;
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  const sse = createSseDecoder();
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      for (const raw of sse.push(decoder.decode(value, { stream: true }))) {
        const event = toChatEvent(raw);
        if (event) {
          handlers.onEvent(event);
        }
      }
    }
    for (const raw of sse.flush()) {
      const event = toChatEvent(raw);
      if (event) {
        handlers.onEvent(event);
      }
    }
  } catch (error) {
    handlers.onNetworkError(error);
  }
}

/** Fetches full source text for a chunk (the citation drill-down). Throws on a non-2xx. */
export async function fetchChunk(chunkId: string, signal?: AbortSignal): Promise<ChunkView> {
  const response = await fetch(`/api/chunks/${chunkId}`, { headers: authHeaders(), signal });
  if (!response.ok) {
    let message = `Could not load source (${response.status})`;
    try {
      message = ((await response.json()) as ApiError).message;
    } catch {
      // keep the fallback
    }
    throw new Error(message);
  }
  return (await response.json()) as ChunkView;
}
