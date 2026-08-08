package com.atlas.core.chat;

import java.util.UUID;

/**
 * Request body for {@code POST /api/chat}.
 *
 * <ul>
 *   <li>{@code question} — the user's question. Required: a blank/absent question is a 400, the
 *       same way search rejects a blank query (see {@link ChatRequestValidation}).
 *   <li>{@code conversationId} — optional. Absent means "start a new conversation" and the server
 *       generates one; supplying an existing id continues that multi-turn conversation. The chosen
 *       id is echoed back in {@link ChatResponse#conversationId()}.
 *   <li>{@code topK} — optional override of how many chunks retrieval pulls for the answer's
 *       context. When absent the endpoint applies the same default and cap search uses.
 * </ul>
 *
 * <p>Unknown fields are rejected (400) by the global strict-deserialization setting ({@code
 * spring.jackson.deserialization.fail-on-unknown-properties}), consistent with search.
 */
public record ChatRequest(String question, UUID conversationId, Integer topK) {

  /** Convenience for callers (and tests) that only supply a question. */
  public ChatRequest(String question) {
    this(question, null, null);
  }
}
