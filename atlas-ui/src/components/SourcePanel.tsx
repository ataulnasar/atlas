import { useEffect, useState } from "react";
import type { Citation, ChunkView } from "../api/types";
import { fetchChunk } from "../api/chatClient";

type LoadState =
  | { status: "loading" }
  | { status: "loaded"; chunk: ChunkView }
  | { status: "error"; message: string };

/**
 * The citation drill-down: opens when a [cN] chip is clicked and loads the full source passage from
 * GET /api/chunks/{id}. This is the basic panel; the dedicated "Add citation display in UI" card can
 * enrich it (highlighting the cited span, neighbouring-chunk navigation, etc.).
 */
export function SourcePanel({
  citation,
  onClose,
}: {
  citation: Citation | null;
  onClose: () => void;
}) {
  const [state, setState] = useState<LoadState>({ status: "loading" });

  useEffect(() => {
    if (!citation) {
      return;
    }
    const controller = new AbortController();
    setState({ status: "loading" });
    fetchChunk(citation.chunkId, controller.signal)
      .then((chunk) => setState({ status: "loaded", chunk }))
      .catch((error: unknown) => {
        if (controller.signal.aborted) {
          return;
        }
        setState({
          status: "error",
          message: error instanceof Error ? error.message : "Could not load this source.",
        });
      });
    return () => controller.abort();
  }, [citation]);

  // Close on Escape whenever the panel is open.
  useEffect(() => {
    if (!citation) {
      return;
    }
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [citation, onClose]);

  if (!citation) {
    return null;
  }

  return (
    <>
      <div className="panel-backdrop" onClick={onClose} aria-hidden="true" />
      <aside className="source-panel" aria-label="Cited source">
        <div className="source-head">
          <div>
            <p className="source-cite">{citation.citationId}</p>
            <p className="source-file">{citation.documentFilename}</p>
            <p className="source-pages tabular">
              pp. {citation.startPage}–{citation.endPage}
            </p>
          </div>
          <button type="button" className="source-close" onClick={onClose} aria-label="Close source">
            Close
          </button>
        </div>
        <div className="source-body">
          {state.status === "loading" && <p className="source-status">Loading source…</p>}
          {state.status === "error" && (
            <div className="notice" role="status">
              <span className="notice-label">Couldn’t load source</span>
              <p className="notice-body">{state.message}</p>
            </div>
          )}
          {state.status === "loaded" && (
            <>
              <p className="source-meta tabular">Chunk #{state.chunk.chunkIndex}</p>
              <p className="source-text">{state.chunk.content}</p>
            </>
          )}
        </div>
      </aside>
    </>
  );
}
