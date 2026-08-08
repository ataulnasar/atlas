import { useCallback, useRef, useState } from "react";
import type { ApiError, Citation } from "../api/types";
import { streamChat } from "../api/chatClient";
import { reduceAssistant, type AssistantMessage, type ChatEvent, type Message } from "../state/chat";

let counter = 0;
const nextId = () => `m${Date.now()}-${counter++}`;

function noticeForPreStream(status: number, error: ApiError): string {
  if (status === 503) {
    return error.message; // generation_disabled: server already explains search still works
  }
  if (status === 400) {
    return error.message;
  }
  return error.message || `The request failed (${status}).`;
}

const NETWORK_NOTICE =
  "Couldn’t reach Atlas. Check that the API is running on port 8080, then try again.";

export interface UseChat {
  messages: Message[];
  streaming: boolean;
  conversationId: string | null;
  activeCitation: Citation | null;
  send: (question: string) => void;
  openSource: (citation: Citation) => void;
  closeSource: () => void;
}

export function useChat(): UseChat {
  const [messages, setMessages] = useState<Message[]>([]);
  const [streaming, setStreaming] = useState(false);
  const [activeCitation, setActiveCitation] = useState<Citation | null>(null);
  const conversationIdRef = useRef<string | null>(null);
  const [conversationId, setConversationId] = useState<string | null>(null);

  // Apply an update to the most recent assistant message (the in-flight turn).
  const patchAssistant = useCallback(
    (patch: (msg: AssistantMessage) => AssistantMessage) => {
      setMessages((prev) => {
        const next = [...prev];
        for (let i = next.length - 1; i >= 0; i--) {
          const msg = next[i];
          if (msg.role === "assistant") {
            next[i] = patch(msg);
            break;
          }
        }
        return next;
      });
    },
    [],
  );

  const send = useCallback(
    (question: string) => {
      const q = question.trim();
      if (q === "" || streaming) {
        return;
      }
      const userMessage: Message = { id: nextId(), role: "user", text: q };
      const assistant: AssistantMessage = {
        id: nextId(),
        role: "assistant",
        status: "streaming",
        text: "",
        citations: [],
      };
      setMessages((prev) => [...prev, userMessage, assistant]);
      setStreaming(true);

      void streamChat(
        { question: q, conversationId: conversationIdRef.current },
        {
          onEvent: (event: ChatEvent) => {
            if (event.type === "done") {
              conversationIdRef.current = event.conversationId;
              setConversationId(event.conversationId);
            }
            patchAssistant((msg) => reduceAssistant(msg, event));
          },
          onPreStreamError: (status, error) => {
            patchAssistant((msg) => ({
              ...msg,
              status: "error",
              notice: noticeForPreStream(status, error),
            }));
          },
          onNetworkError: () => {
            patchAssistant((msg) => ({ ...msg, status: "error", notice: NETWORK_NOTICE }));
          },
        },
      ).finally(() => setStreaming(false));
    },
    [streaming, patchAssistant],
  );

  const openSource = useCallback((citation: Citation) => setActiveCitation(citation), []);
  const closeSource = useCallback(() => setActiveCitation(null), []);

  return {
    messages,
    streaming,
    conversationId,
    activeCitation,
    send,
    openSource,
    closeSource,
  };
}
