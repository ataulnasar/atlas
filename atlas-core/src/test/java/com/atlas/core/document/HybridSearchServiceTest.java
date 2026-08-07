package com.atlas.core.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for the pure RRF fusion math — no Spring, no database. */
class HybridSearchServiceTest {

  private static final int RRF_K = 60;

  @Test
  void fusesRanksWithHandComputedReciprocalRankScores() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID c = UUID.randomUUID();
    UUID d = UUID.randomUUID();

    // vector list ranks: A=1, B=2, C=3 ; keyword list ranks: C=1, A=2, D=3
    List<SearchHit> vectorHits = List.of(hit(a), hit(b), hit(c));
    List<SearchHit> keywordHits = List.of(hit(c), hit(a), hit(d));

    List<HybridSearchHit> fused = HybridSearchService.fuse(vectorHits, keywordHits, RRF_K, 10);

    // A: 1/61 + 1/62 = 0.0325224 ; C: 1/63 + 1/61 = 0.0322664 ; B: 1/62 = 0.0161290 ;
    // D: 1/63 = 0.0158730  → order A, C, B, D
    assertThat(fused).extracting(HybridSearchHit::chunkId).containsExactly(a, c, b, d);

    HybridSearchHit hitA = fused.get(0);
    assertThat(hitA.score()).isCloseTo(1.0 / 61 + 1.0 / 62, within(1e-6));
    assertThat(hitA.foundBy()).isEqualTo("both");
    assertThat(hitA.vectorRank()).isEqualTo(1);
    assertThat(hitA.keywordRank()).isEqualTo(2);

    HybridSearchHit hitB = fused.get(2);
    assertThat(hitB.score()).isCloseTo(1.0 / 62, within(1e-6));
    assertThat(hitB.foundBy()).isEqualTo("vector");
    assertThat(hitB.vectorRank()).isEqualTo(2);
    assertThat(hitB.keywordRank()).isNull();

    HybridSearchHit hitD = fused.get(3);
    assertThat(hitD.score()).isCloseTo(1.0 / 63, within(1e-6));
    assertThat(hitD.foundBy()).isEqualTo("keyword");
    assertThat(hitD.vectorRank()).isNull();
    assertThat(hitD.keywordRank()).isEqualTo(3);
  }

  @Test
  void aChunkMidRankedInBothListsOutranksAChunkTopOfOnlyOne() {
    UUID midInBoth = UUID.randomUUID();
    UUID topOfVectorOnly = UUID.randomUUID();
    UUID topOfKeywordOnly = UUID.randomUUID();

    // midInBoth is #2 in each list; the others are #1 in exactly one list.
    List<SearchHit> vectorHits = List.of(hit(topOfVectorOnly), hit(midInBoth));
    List<SearchHit> keywordHits = List.of(hit(topOfKeywordOnly), hit(midInBoth));

    List<HybridSearchHit> fused = HybridSearchService.fuse(vectorHits, keywordHits, RRF_K, 10);

    // midInBoth: 1/62 + 1/62 = 0.032258 beats either single-list #1: 1/61 = 0.016393.
    assertThat(fused.get(0).chunkId()).isEqualTo(midInBoth);
    assertThat(fused.get(0).foundBy()).isEqualTo("both");
    double midScore = fused.get(0).score();
    assertThat(fused)
        .filteredOn(h -> !h.chunkId().equals(midInBoth))
        .allSatisfy(h -> assertThat(h.score()).isLessThan(midScore));
  }

  @Test
  void topKTruncatesTheFusedResults() {
    List<SearchHit> vectorHits =
        List.of(hit(UUID.randomUUID()), hit(UUID.randomUUID()), hit(UUID.randomUUID()));

    List<HybridSearchHit> fused = HybridSearchService.fuse(vectorHits, List.of(), RRF_K, 2);

    assertThat(fused).hasSize(2);
  }

  private static SearchHit hit(UUID chunkId) {
    // Leg citationId is irrelevant to fusion (fuse re-assigns c1..cN by fused rank).
    Citation citation =
        new Citation("c0", chunkId, UUID.randomUUID(), "doc.txt", "doc.txt", 1, 1, "snippet");
    return new SearchHit(citation, 0, 0.0);
  }
}
