package com.atlas.core.chat;

import com.atlas.core.document.Citation;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One persisted turn as returned by {@code GET /api/conversations/{id}}: the {@code role} ({@code
 * "user"} / {@code "assistant"}), the message {@code content}, the {@code citations} the assistant
 * turn cited (the same {@link Citation} contract as everywhere else; {@code null} for user turns),
 * and {@code createdAt}. Ordered by the conversation's stable sequence in the enclosing {@link
 * ConversationResponse}.
 */
public record ConversationMessageView(
    String role, String content, List<Citation> citations, OffsetDateTime createdAt) {}
