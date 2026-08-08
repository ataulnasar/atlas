import { useEffect, useRef } from "react";
import { Header } from "./components/Header";
import { EmptyState } from "./components/EmptyState";
import { MessageList } from "./components/MessageList";
import { Composer } from "./components/Composer";
import { SourcePanel } from "./components/SourcePanel";
import { useChat } from "./hooks/useChat";

export default function App() {
  const chat = useChat();
  const threadRef = useRef<HTMLDivElement>(null);

  // Keep the newest content in view as tokens stream and turns are added.
  useEffect(() => {
    const el = threadRef.current;
    if (el) {
      el.scrollTop = el.scrollHeight;
    }
  }, [chat.messages]);

  const empty = chat.messages.length === 0;

  return (
    <div className="app">
      <Header />
      <main className="main">
        <div className="thread" ref={threadRef}>
          <div className="thread-inner">
            {empty ? (
              <EmptyState onPick={chat.send} />
            ) : (
              <MessageList messages={chat.messages} onCite={chat.openSource} />
            )}
          </div>
        </div>
        <Composer disabled={chat.streaming} onSend={chat.send} />
      </main>
      <SourcePanel citation={chat.activeCitation} onClose={chat.closeSource} />
    </div>
  );
}
