package com.atlas.core.chat;

import com.atlas.core.document.Citation;
import java.util.List;

/**
 * Payload of the single terminal {@code citations} SSE event, emitted once after all {@code token}
 * events. It carries the reconciled result of citation extraction over the <em>complete</em>
 * answer:
 *
 * <ul>
 *   <li>{@code answer} — the final answer text with its [cN] markers <em>renumbered</em> to c1..cM
 *       in first-appearance order (the streamed deltas carried the model's original labels, e.g.
 *       [c3][c7]); the client swaps its accumulated raw text for this.
 *   <li>{@code citations} — the cited subset, in the same c1..cM numbering, reusing the Phase 2
 *       {@link Citation} contract exactly (identical to the sync {@code ChatResponse.citations}).
 * </ul>
 */
public record StreamCitationsEvent(String answer, List<Citation> citations) {}
