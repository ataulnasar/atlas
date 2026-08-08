package com.atlas.core.generation;

import com.atlas.core.document.Citation;
import java.util.List;

/**
 * The result of parsing a generated answer for [cN] markers: the {@code answer} rewritten so its
 * markers are the stable c1..cM in first-appearance order, and the {@code citations} actually cited
 * — the offered subset, relabelled to match. An answer with no valid markers yields the original
 * text and an empty citation list.
 */
public record CitationExtraction(String answer, List<Citation> citations) {}
