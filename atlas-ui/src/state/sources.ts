import type { Citation } from "../api/types";

/**
 * One cited document, aggregated from an answer's citations — the answer-level complement to the
 * inline [cN] chips. A document cited by several passages appears once, with all its labels and the
 * union of its page ranges.
 */
export interface SourceSummary {
  documentId: string;
  documentFilename: string;
  /** Human page label, e.g. "p. 14", "pp. 55–56", or "pp. 14, 55–56". */
  pages: string;
  /** The citations into this document, in first-appearance order (each carries its cN label). */
  citations: Citation[];
}

interface PageRange {
  start: number;
  end: number;
}

function rangeLabel(range: PageRange): string {
  return range.start === range.end ? `${range.start}` : `${range.start}–${range.end}`;
}

function formatPages(ranges: PageRange[]): string {
  const seen = new Set<string>();
  const unique: PageRange[] = [];
  for (const range of ranges) {
    const key = `${range.start}-${range.end}`;
    if (!seen.has(key)) {
      seen.add(key);
      unique.push(range);
    }
  }
  unique.sort((a, b) => a.start - b.start || a.end - b.end);
  const labels = unique.map(rangeLabel);
  const singlePage = unique.length === 1 && unique[0].start === unique[0].end;
  return `${singlePage ? "p." : "pp."} ${labels.join(", ")}`;
}

/**
 * Groups an answer's cited-subset citations by document, preserving the order in which each document
 * is first cited. Pure — a function of the citations payload alone — so it's unit-testable and the
 * same whether the citations arrived over the stream or the sync response.
 */
export function summarizeSources(citations: Citation[]): SourceSummary[] {
  const byDocument = new Map<string, SourceSummary>();
  for (const citation of citations) {
    const existing = byDocument.get(citation.documentId);
    if (existing) {
      existing.citations.push(citation);
    } else {
      byDocument.set(citation.documentId, {
        documentId: citation.documentId,
        documentFilename: citation.documentFilename,
        pages: "",
        citations: [citation],
      });
    }
  }
  const summaries = [...byDocument.values()];
  for (const summary of summaries) {
    summary.pages = formatPages(
      summary.citations.map((c) => ({ start: c.startPage, end: c.endPage })),
    );
  }
  return summaries;
}
