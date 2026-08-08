package com.atlas.core.generation;

import com.atlas.core.document.Citation;
import java.util.List;

/**
 * The output of {@code ContextAssembler}: the rendered {@code sources} block that goes into the
 * prompt, plus the ordered {@code citations} it used — labelled c1..cN to match the [cN] markers in
 * the rendering. The generator cites against these labels; {@code CitationExtractor} later narrows
 * them to the ones the answer actually referenced.
 */
public record AssembledContext(String sources, List<Citation> citations) {}
