import { useState, type KeyboardEvent } from "react";

export function Composer({
  disabled,
  onSend,
}: {
  disabled: boolean;
  onSend: (question: string) => void;
}) {
  const [value, setValue] = useState("");

  function submit() {
    const q = value.trim();
    if (q === "" || disabled) {
      return;
    }
    onSend(q);
    setValue("");
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    // Enter sends; Shift+Enter inserts a newline.
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      submit();
    }
  }

  return (
    <div className="composer">
      <div className="composer-inner">
        <textarea
          className="composer-input"
          rows={1}
          placeholder="Ask a question about EU digital regulation…"
          value={value}
          disabled={disabled}
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={onKeyDown}
          aria-label="Your question"
        />
        <button
          type="button"
          className="send"
          onClick={submit}
          disabled={disabled || value.trim() === ""}
        >
          {disabled ? "Answering…" : "Ask"}
        </button>
      </div>
      <p className="composer-hint">
        Enter to send · Shift + Enter for a new line · answers are grounded only in ingested sources
      </p>
    </div>
  );
}
