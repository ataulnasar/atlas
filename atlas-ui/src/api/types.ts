// Wire types mirroring atlas-core's contract (com.atlas.core.chat / .document).

/** A cited source, response-scoped label c1..cM. Matches the Citation record exactly. */
export interface Citation {
  citationId: string;
  chunkId: string;
  documentId: string;
  documentFilename: string;
  documentTitle: string;
  startPage: number;
  endPage: number;
  snippet: string;
}

/** Provider token accounting; any field may be null. */
export interface ChatUsage {
  promptTokens: number | null;
  completionTokens: number | null;
  totalTokens: number | null;
  model: string | null;
}

/** Full source text for the citation drill-down (GET /api/chunks/{id}). */
export interface ChunkView {
  chunkId: string;
  documentId: string;
  documentFilename: string;
  documentTitle: string;
  chunkIndex: number;
  startPage: number;
  endPage: number;
  content: string;
}

/** Clean error body atlas-core returns for 4xx/5xx. */
export interface ApiError {
  error: string;
  message: string;
}

export interface ChatRequestBody {
  question: string;
  conversationId?: string | null;
  topK?: number | null;
}
