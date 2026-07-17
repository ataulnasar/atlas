package com.atlas.core.ingestion;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingResult;
import com.knuddels.jtokkit.api.EncodingType;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Splits a {@link ParsedDocument} into token-budgeted chunks, preferring paragraph boundaries,
 * falling back to sentence boundaries, and only hard-splitting by raw token count when a single
 * sentence exceeds the budget on its own. Chunks after the first carry {@code overlapTokens} of
 * trailing context from the previous chunk. See {@link ChunkingProperties} for why the token budget
 * isn't something to change casually.
 */
@Service
public class ChunkingService {

  private static final Pattern PARAGRAPH_BOUNDARY = Pattern.compile("\n{2,}");
  private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?])\\s+");

  private final ChunkingProperties properties;
  private final Encoding encoding;

  ChunkingService(ChunkingProperties properties) {
    this.properties = properties;
    this.encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
  }

  public List<ChunkCandidate> chunk(ParsedDocument document) {
    List<ParsedPage> pages = document.pages();
    if (pages.isEmpty()) {
      return List.of();
    }

    StringBuilder builder = new StringBuilder();
    List<int[]> pageOffsets = new ArrayList<>(); // {pageNumber, start, end}
    for (int i = 0; i < pages.size(); i++) {
      ParsedPage page = pages.get(i);
      int start = builder.length();
      builder.append(page.text());
      pageOffsets.add(new int[] {page.pageNumber(), start, builder.length()});
      if (i < pages.size() - 1) {
        builder.append("\n\n");
      }
    }
    String workingText = builder.toString();
    if (workingText.isBlank()) {
      return List.of();
    }

    // Target size for "new" content per chunk leaves room for overlapTokens of context
    // prepended from the previous chunk, so every finalized chunk (overlap included) stays
    // within maxTokens — see the invariant asserted in ChunkingServiceTest.
    int effectiveMax = properties.maxTokens() - properties.overlapTokens();

    List<int[]> atoms = splitIntoParagraphs(workingText, 0, workingText.length(), effectiveMax);
    List<int[]> groups = pack(workingText, atoms, effectiveMax);

    List<ChunkCandidate> chunks = new ArrayList<>();
    for (int i = 0; i < groups.size(); i++) {
      int[] group = groups.get(i);
      int contentStart = group[0];
      if (i > 0) {
        int[] previousGroup = groups.get(i - 1);
        int overlapStart =
            findOverlapStart(workingText, previousGroup[1], properties.overlapTokens());
        contentStart = Math.max(overlapStart, previousGroup[0]);
      }
      String content = workingText.substring(contentStart, group[1]);
      int[] pageRange = pageRangeFor(pageOffsets, contentStart, group[1]);
      chunks.add(
          new ChunkCandidate(
              i, content, pageRange[0], pageRange[1], encoding.countTokens(content)));
    }
    return chunks;
  }

  private List<int[]> splitIntoParagraphs(String text, int start, int end, int maxTokens) {
    if (encoding.countTokens(text.substring(start, end)) <= maxTokens) {
      return List.of(new int[] {start, end});
    }
    List<int[]> paragraphs = splitOn(text, start, end, PARAGRAPH_BOUNDARY);
    List<int[]> result = new ArrayList<>();
    for (int[] paragraph : paragraphs) {
      result.addAll(splitIntoSentences(text, paragraph[0], paragraph[1], maxTokens));
    }
    return result;
  }

  private List<int[]> splitIntoSentences(String text, int start, int end, int maxTokens) {
    if (encoding.countTokens(text.substring(start, end)) <= maxTokens) {
      return List.of(new int[] {start, end});
    }
    List<int[]> sentences = splitOn(text, start, end, SENTENCE_BOUNDARY);
    if (sentences.size() <= 1) {
      return hardSplitByTokens(text, start, end, maxTokens);
    }
    List<int[]> result = new ArrayList<>();
    for (int[] sentence : sentences) {
      result.addAll(hardSplitByTokens(text, sentence[0], sentence[1], maxTokens));
    }
    return result;
  }

  private List<int[]> splitOn(String text, int start, int end, Pattern boundary) {
    String segment = text.substring(start, end);
    List<int[]> pieces = new ArrayList<>();
    Matcher matcher = boundary.matcher(segment);
    int lastEnd = 0;
    while (matcher.find()) {
      if (matcher.start() > lastEnd) {
        pieces.add(new int[] {start + lastEnd, start + matcher.start()});
      }
      lastEnd = matcher.end();
    }
    if (lastEnd < segment.length()) {
      pieces.add(new int[] {start + lastEnd, start + segment.length()});
    }
    if (pieces.isEmpty()) {
      pieces.add(new int[] {start, end});
    }
    return pieces;
  }

  private List<int[]> hardSplitByTokens(String text, int start, int end, int maxTokens) {
    List<int[]> pieces = new ArrayList<>();
    int pos = start;
    while (pos < end) {
      String remaining = text.substring(pos, end);
      EncodingResult result = encoding.encode(remaining, maxTokens);
      int cut = Math.min(result.getLastProcessedCharacterIndex() + 1, remaining.length());
      if (cut <= 0) {
        cut = 1; // guarantee forward progress even in a pathological zero-progress case
      }
      int snapped = snapBackToWordBoundary(remaining, cut);
      if (snapped <= 0) {
        snapped = cut; // no whitespace anywhere before the cut — a single oversized "word"
      }
      pieces.add(new int[] {pos, pos + snapped});
      pos += snapped;
    }
    return pieces;
  }

  private int snapBackToWordBoundary(String text, int cutIndex) {
    if (cutIndex >= text.length() || Character.isWhitespace(text.charAt(cutIndex))) {
      return cutIndex;
    }
    int i = cutIndex;
    while (i > 0 && !Character.isWhitespace(text.charAt(i - 1))) {
      i--;
    }
    return i;
  }

  private List<int[]> pack(String text, List<int[]> atoms, int maxTokens) {
    List<int[]> groups = new ArrayList<>();
    int groupStart = -1;
    int groupEnd = -1;
    for (int[] atom : atoms) {
      if (groupStart == -1) {
        groupStart = atom[0];
        groupEnd = atom[1];
        continue;
      }
      int candidateTokens = encoding.countTokens(text.substring(groupStart, atom[1]));
      if (candidateTokens <= maxTokens) {
        groupEnd = atom[1];
      } else {
        groups.add(new int[] {groupStart, groupEnd});
        groupStart = atom[0];
        groupEnd = atom[1];
      }
    }
    if (groupStart != -1) {
      groups.add(new int[] {groupStart, groupEnd});
    }
    return groups;
  }

  private int findOverlapStart(String text, int end, int overlapTokens) {
    if (overlapTokens <= 0 || end <= 0) {
      return end;
    }
    int low = 0;
    int high = end;
    int result = end;
    while (low <= high) {
      int mid = low + (high - low) / 2;
      int tokens = encoding.countTokens(text.substring(mid, end));
      if (tokens <= overlapTokens) {
        result = mid;
        high = mid - 1;
      } else {
        low = mid + 1;
      }
    }
    return snapForwardToWordBoundary(text, result);
  }

  private int snapForwardToWordBoundary(String text, int index) {
    if (index <= 0 || index >= text.length() || Character.isWhitespace(text.charAt(index - 1))) {
      return index;
    }
    int i = index;
    while (i < text.length() && !Character.isWhitespace(text.charAt(i))) {
      i++;
    }
    while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
      i++;
    }
    return i;
  }

  private int[] pageRangeFor(List<int[]> pageOffsets, int start, int end) {
    int startPage = -1;
    int endPage = -1;
    for (int[] page : pageOffsets) {
      int pageStart = page[1];
      int pageEnd = page[2];
      if (pageEnd > start && pageStart < end) {
        if (startPage == -1) {
          startPage = page[0];
        }
        endPage = page[0];
      }
    }
    if (startPage == -1) {
      int nearest = pageOffsets.get(0)[0];
      for (int[] page : pageOffsets) {
        if (page[1] <= start) {
          nearest = page[0];
        }
      }
      startPage = nearest;
      endPage = nearest;
    }
    return new int[] {startPage, endPage};
  }
}
