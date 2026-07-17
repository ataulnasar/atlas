package com.atlas.core.ingestion;

/** One page's worth of extracted text. Formats without pages use a single page numbered 1. */
public record ParsedPage(int pageNumber, String text) {}
