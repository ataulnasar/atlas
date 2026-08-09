import { describe, it, expect } from "vitest";
import { summarizeSources } from "./sources";
import type { Citation } from "../api/types";

const cite = (
  citationId: string,
  documentId: string,
  filename: string,
  startPage: number,
  endPage: number,
): Citation => ({
  citationId,
  chunkId: `chunk-${citationId}`,
  documentId,
  documentFilename: filename,
  documentTitle: filename,
  startPage,
  endPage,
  snippet: "…",
});

describe("summarizeSources", () => {
  it("returns nothing for an abstention (no citations)", () => {
    expect(summarizeSources([])).toEqual([]);
  });

  it("lists each document once, in first-cited order, with its labels", () => {
    const summaries = summarizeSources([
      cite("c1", "doc-gdpr", "gdpr.pdf", 55, 56),
      cite("c2", "doc-aiact", "ai-act.pdf", 14, 14),
    ]);
    expect(summaries.map((s) => s.documentFilename)).toEqual(["gdpr.pdf", "ai-act.pdf"]);
    expect(summaries[0].citations.map((c) => c.citationId)).toEqual(["c1"]);
  });

  it("collapses several citations of one document and unions their page ranges", () => {
    const summaries = summarizeSources([
      cite("c1", "doc-aiact", "ai-act.pdf", 55, 56),
      cite("c2", "doc-aiact", "ai-act.pdf", 14, 14),
    ]);
    expect(summaries).toHaveLength(1);
    const [aiact] = summaries;
    expect(aiact.citations.map((c) => c.citationId)).toEqual(["c1", "c2"]);
    // Ranges sorted by start page, de-duplicated, single page rendered without an en dash.
    expect(aiact.pages).toBe("pp. 14, 55–56");
  });

  it("renders a single page as 'p.' and a single range as 'pp.'", () => {
    expect(summarizeSources([cite("c1", "d", "x.pdf", 14, 14)])[0].pages).toBe("p. 14");
    expect(summarizeSources([cite("c1", "d", "x.pdf", 55, 56)])[0].pages).toBe("pp. 55–56");
  });

  it("de-duplicates identical page ranges cited more than once", () => {
    const summaries = summarizeSources([
      cite("c1", "d", "x.pdf", 55, 56),
      cite("c2", "d", "x.pdf", 55, 56),
    ]);
    expect(summaries[0].pages).toBe("pp. 55–56");
    expect(summaries[0].citations).toHaveLength(2);
  });
});
