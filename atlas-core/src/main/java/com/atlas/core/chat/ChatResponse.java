package com.atlas.core.chat;

import com.atlas.core.document.Citation;
import java.util.List;
import java.util.UUID;

/**
 * Response body for {@code POST /api/chat}.
 *
 * <ul>
 *   <li>{@code conversationId} — the resolved conversation id (the one supplied, or the one the
 *       server generated), for the caller to send on the next turn.
 *   <li>{@code answer} — the generated answer text. Its prose references sources by their {@code
 *       citationId} (e.g. "[c1]").
 *   <li>{@code citations} — the sources the answer points at, reusing the Phase 2 {@link Citation}
 *       contract <em>exactly</em> (same fields search returns), so a UI renders search hits and
 *       chat citations with one component.
 *   <li>{@code retrievalMode} — which retrieval actually ran to build the context: {@code "hybrid"}
 *       normally, or {@code "keyword"} when no embedding provider is configured (keyless degrade),
 *       mirroring how the search endpoints behave.
 *   <li>{@code usage} — token/cost metadata; a placeholder until generation lands (see {@link
 *       ChatUsage}).
 * </ul>
 *
 * <p>The SSE streaming variant of this endpoint (a later card) carries the same data over events:
 * token/delta events for {@code answer}, then a terminal {@code citations} event emitting this
 * exact {@link Citation} list, then a {@code done} event carrying this {@link ChatUsage}.
 */
public record ChatResponse(
    UUID conversationId,
    String answer,
    List<Citation> citations,
    String retrievalMode,
    ChatUsage usage) {}
