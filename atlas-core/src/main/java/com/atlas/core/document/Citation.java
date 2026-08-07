package com.atlas.core.document;

import java.util.UUID;

/**
 * A citation: what a generated answer points at when it cites a source. This is the contract Phase
 * 3's chat endpoint emits alongside each answer, and the same shape search results carry (embedded
 * via {@link SearchHit} / {@link HybridSearchHit}) — one definition of "a cited source", grounded
 * in what retrieval already returns.
 *
 * <ul>
 *   <li>{@code citationId} — a short, stable label scoped to a single response ({@code "c1"},
 *       {@code "c2"}, …), so an answer's prose can reference "[c1]" and a UI can line it up with
 *       the source list. Not globally unique; it only means something within the response that
 *       carries it.
 *   <li>{@code documentTitle} — the human-facing document title. The {@code document} table stores
 *       only a filename (no title column — see V2 schema), so today this is the filename; it stays
 *       a distinct field so a real title can be populated later without changing the contract.
 *   <li>{@code snippet} — a short leading excerpt of the cited chunk, for preview. The full text is
 *       available via {@code GET /api/chunks/{chunkId}}.
 * </ul>
 */
public record Citation(
    String citationId,
    UUID chunkId,
    UUID documentId,
    String documentFilename,
    String documentTitle,
    int startPage,
    int endPage,
    String snippet) {}
