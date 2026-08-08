package com.atlas.core.generation;

import com.atlas.core.document.Citation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Parses [cN] markers out of a generated answer and reconciles them with the citations that context
 * assembly offered.
 *
 * <p>Handles both {@code [c1, c2]} and {@code [c1][c2]} forms. The cited sources are renumbered
 * stably to c1..cM in first-appearance order, and the answer is rewritten so its markers match;
 * only the offered subset that was actually cited is returned. A marker for a label that was never
 * offered is dropped (with a WARN); a bracketed span that contains no cN-shaped token — ordinary
 * prose like {@code [see appendix]} — is left untouched. An answer with no valid markers is legal:
 * it comes back unchanged with an empty citation list.
 */
@Component
public class CitationExtractor {

  private static final Logger log = LoggerFactory.getLogger(CitationExtractor.class);

  // A bracketed span, and within it the individual cN labels (any that appear, comma- or
  // adjacency-separated — so [c1, c2] and [c1][c2] both parse).
  private static final Pattern BRACKET_SPAN = Pattern.compile("\\[([^\\]]*)]");
  private static final Pattern LABEL = Pattern.compile("c\\d+");

  /**
   * @param answer the generated answer, possibly carrying [cN] markers
   * @param offered the citations context assembly offered, labelled c1..cN
   */
  public CitationExtraction extract(String answer, List<Citation> offered) {
    Map<String, Citation> byLabel = new HashMap<>();
    for (Citation citation : offered) {
      byLabel.put(citation.citationId(), citation);
    }

    // Preserves first-appearance order, which is exactly the c1..cM renumbering we want.
    Map<String, String> renumbered = new LinkedHashMap<>();
    Set<String> unknownWarned = new HashSet<>();

    Matcher spanMatcher = BRACKET_SPAN.matcher(answer);
    StringBuilder rewritten = new StringBuilder();
    while (spanMatcher.find()) {
      List<String> labels = labelsIn(spanMatcher.group(1));
      if (labels.isEmpty()) {
        // Not a citation span (no cN token) — leave the original text as-is.
        spanMatcher.appendReplacement(rewritten, Matcher.quoteReplacement(spanMatcher.group()));
        continue;
      }
      StringBuilder replacement = new StringBuilder();
      for (String label : labels) {
        if (!byLabel.containsKey(label)) {
          if (unknownWarned.add(label)) {
            log.warn("Dropping unknown citation marker [{}] from generated answer", label);
          }
          continue;
        }
        replacement.append('[').append(newLabelFor(label, renumbered)).append(']');
      }
      spanMatcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement.toString()));
    }
    spanMatcher.appendTail(rewritten);

    List<Citation> citations = new ArrayList<>(renumbered.size());
    renumbered.forEach(
        (oldLabel, newLabel) -> citations.add(byLabel.get(oldLabel).withCitationId(newLabel)));

    if (citations.isEmpty()) {
      log.debug("Generated answer carried no valid citation markers");
    }

    return new CitationExtraction(rewritten.toString(), List.copyOf(citations));
  }

  private static List<String> labelsIn(String span) {
    List<String> labels = new ArrayList<>();
    Matcher labelMatcher = LABEL.matcher(span);
    while (labelMatcher.find()) {
      labels.add(labelMatcher.group());
    }
    return labels;
  }

  private static String newLabelFor(String oldLabel, Map<String, String> renumbered) {
    String existing = renumbered.get(oldLabel);
    if (existing != null) {
      return existing;
    }
    String created = "c" + (renumbered.size() + 1);
    renumbered.put(oldLabel, created);
    return created;
  }
}
