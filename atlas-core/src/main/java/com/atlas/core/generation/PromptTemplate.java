package com.atlas.core.generation;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Assembles the system and user messages for a RAG chat turn from versioned resource templates
 * (under {@code /prompts}), so the prompt wording is reviewable in git rather than concatenated in
 * Java.
 *
 * <ul>
 *   <li>{@link #system()} — the grounding rules: answer only from the sources, cite inline as [cN],
 *       abstain (do not fall back to general knowledge) when the sources don't cover the question,
 *       and answer in the language of the question.
 *   <li>{@link #user(List, String, String)} — an optional conversation tail (the last {@code
 *       atlas.generation.history-turns} Q&amp;A pairs, verbatim), then the sources block, then the
 *       current question.
 * </ul>
 */
@Component
public class PromptTemplate {

  /**
   * The abstention rule, exactly as it appears in {@code system.txt}. Kept as a constant so a test
   * can assert the loaded prompt still carries it — abstention is the behaviour that keeps answers
   * grounded, and it must not silently drift out of the template.
   */
  public static final String ABSTENTION_INSTRUCTION =
      "If the provided sources do not contain the answer, reply that the provided documents do not"
          + " cover this question, and do not answer from general knowledge.";

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

  private final GenerationProperties properties;
  private final String systemPrompt;
  private final String userTemplate;
  private final String historyBlockTemplate;
  private final String historyPairTemplate;

  PromptTemplate(GenerationProperties properties) {
    this.properties = properties;
    this.systemPrompt = load("/prompts/system.txt");
    this.userTemplate = load("/prompts/user-template.txt");
    this.historyBlockTemplate = load("/prompts/history-block.txt");
    this.historyPairTemplate = load("/prompts/history-pair.txt");
  }

  /** The system prompt: the grounding, citation, abstention, and language rules. */
  public String system() {
    return systemPrompt;
  }

  /**
   * The user message: the last {@code atlas.generation.history-turns} pairs of {@code history}
   * (verbatim, oldest first; omitted entirely when there is none), then the {@code sources} block,
   * then the current {@code question}.
   */
  public String user(List<QaPair> history, String sources, String question) {
    return render(
        userTemplate,
        Map.of("history", renderHistory(history), "sources", sources, "question", question));
  }

  private String renderHistory(List<QaPair> history) {
    if (history == null || history.isEmpty() || properties.historyTurns() == 0) {
      return "";
    }
    int from = Math.max(0, history.size() - properties.historyTurns());
    List<QaPair> tail = history.subList(from, history.size());
    String pairs =
        tail.stream()
            .map(
                pair ->
                    render(
                        historyPairTemplate,
                        Map.of("question", pair.question(), "answer", pair.answer())))
            .collect(Collectors.joining("\n\n"));
    return render(historyBlockTemplate, Map.of("pairs", pairs));
  }

  // Single-pass substitution: each {{key}} is replaced once, and substituted values are never
  // re-scanned — so verbatim history or a question that happens to contain "{{sources}}" can't be
  // reinterpreted as a placeholder. Unknown placeholders are left untouched.
  private static String render(String template, Map<String, String> values) {
    Matcher matcher = PLACEHOLDER.matcher(template);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      String replacement = values.getOrDefault(matcher.group(1), matcher.group());
      matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private static String load(String resourcePath) {
    try (InputStream in = PromptTemplate.class.getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IllegalStateException("Prompt template resource not found: " + resourcePath);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read prompt template: " + resourcePath, e);
    }
  }
}
