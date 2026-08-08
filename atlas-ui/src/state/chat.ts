import type { Citation, ChatUsage } from "../api/types";

/**
 * Normalized stream events, decoded from the SSE payloads atlas-core sends
 * (StreamTokenEvent / StreamCitationsEvent / StreamDoneEvent, plus a clean `error`).
 */
export type ChatEvent =
  | { type: "token"; delta: string }
  | { type: "citations"; answer: string; citations: Citation[] }
  | { type: "done"; conversationId: string; retrievalMode: string; usage: ChatUsage }
  | { type: "error"; code: string; message: string };

export type MessageStatus = "streaming" | "complete" | "error";

export interface UserMessage {
  id: string;
  role: "user";
  text: string;
}

export interface AssistantMessage {
  id: string;
  role: "assistant";
  status: MessageStatus;
  /** During streaming: the raw deltas, with the model's ORIGINAL [cN] markers. After the
   *  citations event: the renumbered final answer that the chips are drawn from. */
  text: string;
  citations: Citation[];
  retrievalMode?: string;
  usage?: ChatUsage;
  /** Set when status is "error": the inline system-notice text. */
  notice?: string;
}

export type Message = UserMessage | AssistantMessage;

/**
 * Applies one stream event to the in-flight assistant message. This is the swap logic:
 *
 * - `token` appends the raw delta (markers stay literal — the caller renders them as plain text);
 * - `citations` SWAPS the accumulated raw text for the renumbered answer and attaches the cited
 *   subset — the point at which [cN] become clickable chips;
 * - `done` marks the turn complete and records retrieval mode + usage;
 * - `error` turns the message into an inline error notice.
 *
 * Pure and self-contained so the swap is unit-testable without React or the network.
 */
export function reduceAssistant(message: AssistantMessage, event: ChatEvent): AssistantMessage {
  switch (event.type) {
    case "token":
      return { ...message, text: message.text + event.delta };
    case "citations":
      return { ...message, text: event.answer, citations: event.citations };
    case "done":
      return {
        ...message,
        status: "complete",
        retrievalMode: event.retrievalMode,
        usage: event.usage,
      };
    case "error":
      return { ...message, status: "error", notice: event.message };
    default:
      return message;
  }
}
