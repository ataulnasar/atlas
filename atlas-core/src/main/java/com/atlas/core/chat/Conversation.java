package com.atlas.core.chat;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A chat conversation: the thread that {@link ChatMessage}s belong to. */
public record Conversation(UUID id, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
