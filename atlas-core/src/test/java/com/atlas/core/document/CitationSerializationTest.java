package com.atlas.core.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Locks the JSON wire shapes of the citation contract and the search hits that embed it. These are
 * exact field-set assertions: an accidental rename, a dropped field, or a citation that stops being
 * unwrapped (nesting {@code "citation": {…}}) fails loudly here rather than silently breaking the
 * React UI, the mini-golden spot-check, or Phase 3's chat citations.
 */
class CitationSerializationTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private static final Citation CITATION =
      new Citation("c1", UUID.randomUUID(), UUID.randomUUID(), "gdpr.pdf", "gdpr.pdf", 55, 56, "…");

  @Test
  void citationHasExactlyTheContractFields() throws Exception {
    JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(CITATION));

    assertThat(fieldNames(json))
        .containsExactlyInAnyOrder(
            "citationId",
            "chunkId",
            "documentId",
            "documentFilename",
            "documentTitle",
            "startPage",
            "endPage",
            "snippet");
  }

  @Test
  void searchHitEmbedsTheCitationFlatWithChunkIndexAndScore() throws Exception {
    JsonNode json =
        objectMapper.readTree(objectMapper.writeValueAsString(new SearchHit(CITATION, 15, 0.6452)));

    // Citation fields are flat at the top level (not nested under "citation"), plus the envelope.
    assertThat(fieldNames(json))
        .containsExactlyInAnyOrder(
            "citationId",
            "chunkId",
            "documentId",
            "documentFilename",
            "documentTitle",
            "startPage",
            "endPage",
            "snippet",
            "chunkIndex",
            "score");
    assertThat(json.has("citation")).isFalse();
  }

  @Test
  void hybridSearchHitAddsFusionTransparencyFields() throws Exception {
    JsonNode json =
        objectMapper.readTree(
            objectMapper.writeValueAsString(
                new HybridSearchHit(CITATION, 15, 0.0323, "both", 2, 3)));

    assertThat(fieldNames(json))
        .containsExactlyInAnyOrder(
            "citationId",
            "chunkId",
            "documentId",
            "documentFilename",
            "documentTitle",
            "startPage",
            "endPage",
            "snippet",
            "chunkIndex",
            "score",
            "foundBy",
            "vectorRank",
            "keywordRank");
  }

  @Test
  void nullPerSourceRanksSerializeAsJsonNull() throws Exception {
    JsonNode json =
        objectMapper.readTree(
            objectMapper.writeValueAsString(
                new HybridSearchHit(CITATION, 15, 0.0164, "vector", 1, null)));

    assertThat(json.get("vectorRank").asInt()).isEqualTo(1);
    assertThat(json.get("keywordRank").isNull()).isTrue();
  }

  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }
}
