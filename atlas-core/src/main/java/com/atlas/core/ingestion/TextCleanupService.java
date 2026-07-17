package com.atlas.core.ingestion;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Strips configured noise lines from a {@link ParsedDocument}'s page text before chunking. A line
 * (split on {@code \n}) is removed only if it fully matches one of {@link
 * ParsingProperties#stripLinePatterns()} — a partial match doesn't count, since a footer fragment
 * that merely touches real content should never take that content down with it. With the default
 * empty pattern list this is a no-op, returning the input document unchanged.
 */
@Service
public class TextCleanupService {

  private static final Pattern BLANK_LINE_RUN = Pattern.compile("\n{3,}");

  private final List<Pattern> stripPatterns;

  TextCleanupService(ParsingProperties properties) {
    this.stripPatterns = properties.stripLinePatterns().stream().map(Pattern::compile).toList();
  }

  /**
   * Removes matching lines from every page and collapses the blank-line runs a removal leaves
   * behind, so a stripped line doesn't introduce a phantom paragraph break. Returns the stripped
   * document alongside how many lines were removed, for the caller to log with document context.
   */
  public CleanupResult clean(ParsedDocument document) {
    if (stripPatterns.isEmpty()) {
      return new CleanupResult(document, 0);
    }

    int totalStripped = 0;
    List<ParsedPage> cleanedPages = new ArrayList<>(document.pages().size());
    for (ParsedPage page : document.pages()) {
      StrippedText result = stripLines(page.text());
      totalStripped += result.strippedCount();
      cleanedPages.add(new ParsedPage(page.pageNumber(), result.text()));
    }

    String fullText =
        cleanedPages.stream().map(ParsedPage::text).collect(Collectors.joining("\n\n"));
    return new CleanupResult(new ParsedDocument(fullText, cleanedPages), totalStripped);
  }

  private StrippedText stripLines(String text) {
    String[] lines = text.split("\n", -1);
    StringBuilder builder = new StringBuilder();
    int strippedCount = 0;
    for (int i = 0; i < lines.length; i++) {
      if (matchesAnyPattern(lines[i])) {
        strippedCount++;
        continue;
      }
      if (builder.length() > 0) {
        builder.append("\n");
      }
      builder.append(lines[i]);
    }
    String collapsed = BLANK_LINE_RUN.matcher(builder).replaceAll("\n\n");
    return new StrippedText(collapsed, strippedCount);
  }

  private boolean matchesAnyPattern(String line) {
    for (Pattern pattern : stripPatterns) {
      if (pattern.matcher(line).matches()) {
        return true;
      }
    }
    return false;
  }

  /** {@code strippedLineCount} is the total across every page of the document. */
  public record CleanupResult(ParsedDocument document, int strippedLineCount) {}

  private record StrippedText(String text, int strippedCount) {}
}
