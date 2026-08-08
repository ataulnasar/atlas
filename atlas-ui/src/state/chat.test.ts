import { describe, it, expect } from "vitest";
import { reduceAssistant, type AssistantMessage } from "./chat";
import type { Citation } from "../api/types";

const base: AssistantMessage = {
  id: "a1",
  role: "assistant",
  status: "streaming",
  text: "",
  citations: [],
};

const cite = (id: string, chunkId: string): Citation => ({
  citationId: id,
  chunkId,
  documentId: "d1",
  documentFilename: "ai-act.pdf",
  documentTitle: "ai-act.pdf",
  startPage: 53,
  endPage: 54,
  snippet: "…",
});

describe("reduceAssistant — the streaming swap", () => {
  it("appends raw token deltas verbatim, keeping the model's original markers", () => {
    let msg = base;
    for (const delta of ["A system is ", "high-risk ", "[c2]", "[c1]", "."]) {
      msg = reduceAssistant(msg, { type: "token", delta });
    }
    expect(msg.text).toBe("A system is high-risk [c2][c1].");
    expect(msg.citations).toEqual([]); // no chips yet — still streaming raw
    expect(msg.status).toBe("streaming");
  });

  it("swaps in the renumbered answer and attaches the cited subset on the citations event", () => {
    let msg = reduceAssistant(base, { type: "token", delta: "high-risk [c2][c1]." });
    const citations = [cite("c1", "chunk-B"), cite("c2", "chunk-A")];

    msg = reduceAssistant(msg, {
      type: "citations",
      answer: "high-risk [c1][c2].",
      citations,
    });

    // The raw text is replaced by the reconciled, renumbered answer.
    expect(msg.text).toBe("high-risk [c1][c2].");
    expect(msg.citations).toBe(citations);
  });

  it("marks complete and records retrieval mode + usage on done", () => {
    let msg = reduceAssistant(base, { type: "token", delta: "answer" });
    msg = reduceAssistant(msg, {
      type: "done",
      conversationId: "conv-1",
      retrievalMode: "hybrid",
      usage: { promptTokens: 10, completionTokens: 20, totalTokens: 30, model: "gpt-5-mini" },
    });
    expect(msg.status).toBe("complete");
    expect(msg.retrievalMode).toBe("hybrid");
    expect(msg.usage?.totalTokens).toBe(30);
  });

  it("turns into an inline error notice on an error event", () => {
    const msg = reduceAssistant(base, {
      type: "error",
      code: "generation_failed",
      message: "The answer generator failed to produce a response",
    });
    expect(msg.status).toBe("error");
    expect(msg.notice).toBe("The answer generator failed to produce a response");
  });
});
