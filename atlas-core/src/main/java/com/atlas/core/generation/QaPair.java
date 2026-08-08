package com.atlas.core.generation;

/**
 * One prior turn of a conversation — the user's {@code question} and the assistant's {@code answer}
 * — replayed verbatim into the prompt as recent context. {@code PromptTemplate} keeps only the last
 * {@code atlas.generation.history-turns} of these.
 */
public record QaPair(String question, String answer) {}
