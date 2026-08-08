package com.atlas.core.chat;

import com.atlas.core.document.Citation;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One persisted turn in a conversation. {@code seq} is the stable per-conversation ordering. {@code
 * citations} holds the assistant turn's sources (the Phase 2 {@link Citation} contract, stored as
 * JSONB); it is {@code null} for user turns.
 */
public record ChatMessage(
    UUID id,
    UUID conversationId,
    int seq,
    MessageRole role,
    String content,
    List<Citation> citations,
    OffsetDateTime createdAt) {}
