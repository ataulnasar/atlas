package com.atlas.core.generation;

import java.util.Locale;

/** Model-family capability checks that decide how the request options are built. */
final class ChatModels {

  private ChatModels() {}

  /**
   * Whether a model accepts an explicit {@code temperature} parameter.
   *
   * <p>OpenAI's reasoning-model families — the GPT-5 line and the o-series (o1/o3/o4) — only
   * support the default temperature (1) and reject any explicit value with a 400 ("Unsupported
   * value: 'temperature' does not support 0.1 with this model. Only the default (1) value is
   * supported."). For those, temperature must be omitted from the request; for the classic chat
   * families (gpt-4o, gpt-4.1, gpt-3.5, …) it is honoured. The default {@code gpt-5-mini} therefore
   * runs <em>without</em> an explicit temperature.
   */
  static boolean supportsCustomTemperature(String model) {
    String normalized = model.toLowerCase(Locale.ROOT);
    return !(normalized.startsWith("gpt-5")
        || normalized.startsWith("o1")
        || normalized.startsWith("o3")
        || normalized.startsWith("o4"));
  }
}
