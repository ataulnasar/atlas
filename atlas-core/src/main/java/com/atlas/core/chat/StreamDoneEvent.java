package com.atlas.core.chat;

import java.util.UUID;

/**
 * Payload of the final {@code done} SSE event, emitted last to close a successful stream. Carries
 * the {@code conversationId} (for the client to send on the next turn), the {@code retrievalMode}
 * that actually ran, and the provider {@link ChatUsage}. Together with {@link StreamCitationsEvent}
 * and the {@code token} deltas, these events carry exactly the data of the sync {@code
 * ChatResponse}.
 */
public record StreamDoneEvent(UUID conversationId, String retrievalMode, ChatUsage usage) {}
