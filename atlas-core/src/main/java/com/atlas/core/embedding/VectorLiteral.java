package com.atlas.core.embedding;

/**
 * Formats a float vector as pgvector's text literal, e.g. {@code [0.1,0.2,0.3]}, so it can be bound
 * as a plain string and cast server-side ({@code CAST(? AS vector)}) — no pgvector-specific JDBC
 * type required.
 */
public final class VectorLiteral {

  private VectorLiteral() {}

  public static String format(float[] vector) {
    StringBuilder builder = new StringBuilder(vector.length * 12 + 2);
    builder.append('[');
    for (int i = 0; i < vector.length; i++) {
      if (i > 0) {
        builder.append(',');
      }
      builder.append(vector[i]);
    }
    builder.append(']');
    return builder.toString();
  }
}
