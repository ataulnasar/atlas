package com.atlas.core.chat;

import java.util.Locale;

/**
 * Who authored a {@link ChatMessage}. Mirrors the {@code role} CHECK constraint on the {@code
 * message} table, which stores the lowercase wire values {@code 'user'} / {@code 'assistant'}.
 */
public enum MessageRole {
  USER,
  ASSISTANT;

  /** The lowercase value stored in the {@code message.role} column. */
  String dbValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  static MessageRole fromDbValue(String value) {
    return MessageRole.valueOf(value.toUpperCase(Locale.ROOT));
  }
}
