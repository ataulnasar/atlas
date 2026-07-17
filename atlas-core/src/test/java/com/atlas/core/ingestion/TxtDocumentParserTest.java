package com.atlas.core.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.core.document.SupportedContentType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TxtDocumentParserTest {

  @TempDir private Path tempDir;

  private final TxtDocumentParser parser = new TxtDocumentParser();

  @Test
  void supportsOnlyTxt() {
    assertThat(parser.supports(SupportedContentType.TXT)).isTrue();
    assertThat(parser.supports(SupportedContentType.PDF)).isFalse();
    assertThat(parser.supports(SupportedContentType.DOCX)).isFalse();
  }

  @Test
  void parsesTheWholeFileAsASinglePage() throws IOException {
    Path file = tempDir.resolve("sample.txt");
    Files.writeString(file, "hello world", StandardCharsets.UTF_8);

    ParsedDocument parsed = parser.parse(file);

    assertThat(parsed.fullText()).isEqualTo("hello world");
    assertThat(parsed.pageCount()).isEqualTo(1);
    assertThat(parsed.pages()).hasSize(1);
    assertThat(parsed.pages().get(0).pageNumber()).isEqualTo(1);
    assertThat(parsed.pages().get(0).text()).isEqualTo("hello world");
  }

  @Test
  void wrapsIoFailuresAsCorruptDocumentException() {
    Path missingFile = tempDir.resolve("does-not-exist.txt");

    assertThatThrownBy(() -> parser.parse(missingFile))
        .isInstanceOf(CorruptDocumentException.class)
        .satisfies(
            e ->
                assertThat(((CorruptDocumentException) e).contentType())
                    .isEqualTo(SupportedContentType.TXT));
  }
}
