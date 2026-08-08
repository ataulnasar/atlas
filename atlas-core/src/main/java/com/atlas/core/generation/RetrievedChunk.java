package com.atlas.core.generation;

import com.atlas.core.document.Citation;

/**
 * A ranked chunk ready to be assembled into prompt context: the {@link Citation} for the source,
 * the chunk's <em>full</em> {@code content} (what gets rendered — not the truncated preview snippet
 * a search {@code Citation} carries), and its stored {@code tokenCount} (the {@code
 * chunk.token_count} column) so budgeting never has to re-tokenize.
 *
 * <p>This is the assembler's input unit because a bare search hit is insufficient here: its
 * citation snippet is clipped to a preview length, and it carries no token count — both of which
 * context assembly needs to render whole chunks and enforce a token budget. The citation's {@code
 * citationId} is not authoritative at this stage; {@code ContextAssembler} assigns the c1..cN
 * labels over the chunks that actually fit.
 */
public record RetrievedChunk(Citation citation, String content, int tokenCount) {}
