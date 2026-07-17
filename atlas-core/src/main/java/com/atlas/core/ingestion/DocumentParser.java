package com.atlas.core.ingestion;

import com.atlas.core.document.SupportedContentType;
import java.nio.file.Path;

/**
 * Extracts text (and, where applicable, page-level structure) from a stored document.
 * Implementations are registered as Spring beans and selected via {@link DocumentParserRegistry}.
 */
public interface DocumentParser {

  boolean supports(SupportedContentType contentType);

  ParsedDocument parse(Path source) throws ParseException;
}
