import { Fragment } from "react";
import type { Citation } from "../api/types";
import type { AssistantMessage, Message } from "../state/chat";

const MARKER = /(\[c\d+\])/g;
const MARKER_ONE = /^\[(c\d+)\]$/;

/** Renders answer prose, turning [cN] into clickable chips once citations are known (post-swap). */
function AnswerText({
  text,
  citations,
  onCite,
}: {
  text: string;
  citations: Citation[];
  onCite: (c: Citation) => void;
}) {
  if (citations.length === 0) {
    return <>{text}</>; // streaming raw text, or an abstention with no sources — markers stay literal
  }
  const byId = new Map(citations.map((c) => [c.citationId, c]));
  return (
    <>
      {text.split(MARKER).map((part, i) => {
        const match = MARKER_ONE.exec(part);
        const citation = match ? byId.get(match[1]) : undefined;
        if (match && citation) {
          return (
            <button
              key={i}
              type="button"
              className="chip"
              onClick={() => onCite(citation)}
              title={`${citation.documentFilename}, pp. ${citation.startPage}–${citation.endPage}`}
            >
              {match[1]}
            </button>
          );
        }
        return <Fragment key={i}>{part}</Fragment>;
      })}
    </>
  );
}

function AssistantTurn({
  message,
  onCite,
}: {
  message: AssistantMessage;
  onCite: (c: Citation) => void;
}) {
  if (message.status === "error") {
    return (
      <div className="turn turn-assistant">
        <p className="speaker">Atlas</p>
        <div className="notice" role="status">
          <span className="notice-label">Couldn’t complete</span>
          <p className="notice-body">{message.notice}</p>
        </div>
      </div>
    );
  }

  const waiting = message.status === "streaming" && message.text === "";
  const uncited = message.status === "complete" && message.citations.length === 0;

  return (
    <div className="turn turn-assistant">
      <p className="speaker">Atlas</p>
      {waiting ? (
        <p className="thinking" role="status" aria-live="polite">
          Searching the corpus and drafting a grounded answer
          <span className="dots" aria-hidden="true">
            <span></span>
            <span></span>
            <span></span>
          </span>
        </p>
      ) : (
        <div className="answer">
          <AnswerText text={message.text} citations={message.citations} onCite={onCite} />
          {message.status === "streaming" && <span className="caret" aria-hidden="true" />}
        </div>
      )}
      {message.status === "complete" && (
        <p className="turn-meta">
          {message.citations.length > 0 ? (
            <span>
              {message.citations.length} source{message.citations.length === 1 ? "" : "s"} cited
            </span>
          ) : (
            uncited && <span>No sources cited</span>
          )}
          {message.retrievalMode && <span>{message.retrievalMode} retrieval</span>}
          {message.usage?.totalTokens != null && (
            <span className="tabular">{message.usage.totalTokens.toLocaleString()} tokens</span>
          )}
        </p>
      )}
    </div>
  );
}

export function MessageList({
  messages,
  onCite,
}: {
  messages: Message[];
  onCite: (c: Citation) => void;
}) {
  return (
    <div className="messages">
      {messages.map((message) =>
        message.role === "user" ? (
          <div className="turn turn-user" key={message.id}>
            <p className="speaker">You</p>
            <p className="user-text">{message.text}</p>
          </div>
        ) : (
          <AssistantTurn key={message.id} message={message} onCite={onCite} />
        ),
      )}
    </div>
  );
}
