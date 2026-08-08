package com.atlas.core.chat;

/**
 * Payload of a {@code token} SSE event: one raw answer delta as it arrives from the model. The
 * {@code delta} still carries whatever [cN] markers the model emitted against the offered source
 * labels — the client renders these as they stream and reconciles them when the terminal {@code
 * citations} event arrives (see {@link StreamCitationsEvent}).
 */
public record StreamTokenEvent(String delta) {}
