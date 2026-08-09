package com.atlas.core.chat;

import java.util.List;
import java.util.UUID;

/**
 * Response body for {@code GET /api/conversations/{id}} — the ordered turns of a conversation, so a
 * client can reload a thread it no longer holds (e.g. after a page refresh). The minimal read path;
 * a conversation-<em>list</em> endpoint is intentionally out of v1 scope (there is no per-user
 * ownership model yet — see docs/plan.md).
 */
public record ConversationResponse(UUID conversationId, List<ConversationMessageView> messages) {}
