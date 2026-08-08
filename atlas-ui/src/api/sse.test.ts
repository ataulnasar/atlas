import { describe, it, expect } from "vitest";
import { createSseDecoder, parseFrame } from "./sse";

describe("parseFrame", () => {
  it("reads event name and data, stripping one leading space", () => {
    expect(parseFrame("event: token\ndata: {\"delta\":\"Hi\"}")).toEqual({
      event: "token",
      data: '{"delta":"Hi"}',
    });
  });

  it("defaults the event name to 'message' and joins multiple data lines with newline", () => {
    expect(parseFrame("data: line1\ndata: line2")).toEqual({
      event: "message",
      data: "line1\nline2",
    });
  });

  it("ignores comment lines and returns null when there is no data", () => {
    expect(parseFrame(": keep-alive")).toBeNull();
    expect(parseFrame("event: done")).toBeNull();
  });
});

describe("createSseDecoder — chunk boundary handling", () => {
  it("emits an event once its terminating blank line arrives, not before", () => {
    const decoder = createSseDecoder();
    expect(decoder.push("event: token\n")).toEqual([]);
    expect(decoder.push('data: {"delta":"A"}')).toEqual([]); // no blank line yet
    expect(decoder.push("\n\n")).toEqual([{ event: "token", data: '{"delta":"A"}' }]);
  });

  it("reassembles an event split across arbitrary byte boundaries", () => {
    const decoder = createSseDecoder();
    const events = [
      ...decoder.push("eve"),
      ...decoder.push("nt: citat"),
      ...decoder.push('ions\ndata: {"answer":"x","cita'),
      ...decoder.push('tions":[]}\n\n'),
    ];
    expect(events).toEqual([{ event: "citations", data: '{"answer":"x","citations":[]}' }]);
  });

  it("emits multiple events delivered in a single chunk, in order", () => {
    const decoder = createSseDecoder();
    const chunk =
      'event: token\ndata: {"delta":"A"}\n\n' +
      'event: token\ndata: {"delta":"B"}\n\n' +
      'event: done\ndata: {"conversationId":"c1"}\n\n';
    expect(decoder.push(chunk)).toEqual([
      { event: "token", data: '{"delta":"A"}' },
      { event: "token", data: '{"delta":"B"}' },
      { event: "done", data: '{"conversationId":"c1"}' },
    ]);
  });

  it("normalizes CRLF frame delimiters", () => {
    const decoder = createSseDecoder();
    expect(decoder.push('event: token\r\ndata: {"delta":"A"}\r\n\r\n')).toEqual([
      { event: "token", data: '{"delta":"A"}' },
    ]);
  });

  it("keeps a trailing partial frame across pushes and recovers it on flush", () => {
    const decoder = createSseDecoder();
    expect(decoder.push('event: done\ndata: {"conversationId":"c1"}')).toEqual([]);
    expect(decoder.flush()).toEqual([{ event: "done", data: '{"conversationId":"c1"}' }]);
  });
});
