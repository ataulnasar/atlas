package com.atlas.core.document;

/** Clean, stable-shaped error body for document API failures. */
public record ApiError(String error, String message) {}
