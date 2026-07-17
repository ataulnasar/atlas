package com.atlas.core.ingestion;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Line-level noise-stripping patterns applied to parsed page text before chunking, by {@link
 * TextCleanupService}. Empty by default — Atlas core ships no patterns of its own, since what
 * counts as boilerplate (a repeating page footer, a header, a watermark line) is a property of the
 * document source a deployment ingests, not of Atlas itself. See docker/.env.example and
 * corpus/README.md for the EUR-Lex patterns used with the demo corpus.
 */
@ConfigurationProperties(prefix = "atlas.parsing")
public record ParsingProperties(List<String> stripLinePatterns) {

  public ParsingProperties {
    stripLinePatterns = stripLinePatterns == null ? List.of() : List.copyOf(stripLinePatterns);
  }
}
