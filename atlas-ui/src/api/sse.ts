// A minimal, spec-shaped Server-Sent Events decoder.
//
// EventSource can't POST, so we consume the text/event-stream from a fetch() body reader
// ourselves. A network read yields arbitrary byte chunks that can split an event mid-line or
// mid-frame, so decoding is stateful: buffer text, emit only complete events (terminated by a
// blank line), and keep the trailing partial for the next chunk.

export interface SseEvent {
  /** The event name (`event:` field); "message" when unspecified, per the SSE spec. */
  event: string;
  /** The data payload; multiple `data:` lines are joined with "\n". */
  data: string;
}

/**
 * Parses one already-delimited event block (the text between blank lines). Returns null for a
 * block that carries no data (e.g. a lone comment or keep-alive), which callers should skip.
 */
export function parseFrame(frame: string): SseEvent | null {
  let event = "message";
  const dataLines: string[] = [];

  for (const line of frame.split("\n")) {
    if (line === "" || line.startsWith(":")) {
      continue; // blank line or comment (":" prefix) — ignored
    }
    const colon = line.indexOf(":");
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) {
      value = value.slice(1); // strip a single leading space after the colon, per spec
    }
    if (field === "event") {
      event = value;
    } else if (field === "data") {
      dataLines.push(value);
    }
  }

  if (dataLines.length === 0) {
    return null;
  }
  return { event, data: dataLines.join("\n") };
}

/**
 * A stateful decoder. Feed it decoded text chunks with {@link SseDecoder.push}; it returns the
 * events completed by that chunk and retains any partial frame. Call {@link SseDecoder.flush} once
 * the stream ends to recover a final frame that wasn't blank-line terminated.
 */
export interface SseDecoder {
  push(chunk: string): SseEvent[];
  flush(): SseEvent[];
}

export function createSseDecoder(): SseDecoder {
  let buffer = "";

  return {
    push(chunk: string): SseEvent[] {
      // Normalize CRLF / CR so "\n\n" is the single frame delimiter we scan for.
      buffer += chunk.replace(/\r\n/g, "\n").replace(/\r/g, "\n");
      const events: SseEvent[] = [];
      let boundary = buffer.indexOf("\n\n");
      while (boundary !== -1) {
        const frame = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        const event = parseFrame(frame);
        if (event) {
          events.push(event);
        }
        boundary = buffer.indexOf("\n\n");
      }
      return events;
    },

    flush(): SseEvent[] {
      const rest = buffer.trim();
      buffer = "";
      if (rest === "") {
        return [];
      }
      const event = parseFrame(rest);
      return event ? [event] : [];
    },
  };
}
