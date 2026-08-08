package com.atlas.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.core.document.ApiError;
import com.atlas.core.document.Citation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Locks the JSON wire shapes of the chat API schema and its request validation — exact field-set
 * assertions so an accidental rename or dropped field fails loudly, the way {@code
 * CitationSerializationTest} does for the citation contract. In particular it pins that {@code
 * ChatResponse.citations} reuses the Phase 2 {@link Citation} contract byte-for-byte.
 */
class ChatSchemaSerializationTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private static final Citation CITATION =
      new Citation("c1", UUID.randomUUID(), UUID.randomUUID(), "gdpr.pdf", "gdpr.pdf", 55, 56, "…");

  @Test
  void chatResponseHasExactlyTheContractFields() throws Exception {
    ChatResponse response =
        new ChatResponse(
            UUID.randomUUID(),
            "GDPR requires a DPO in cases X and Y [c1].",
            List.of(CITATION),
            "hybrid",
            ChatUsage.placeholder());

    JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

    assertThat(fieldNames(json))
        .containsExactlyInAnyOrder(
            "conversationId", "answer", "citations", "retrievalMode", "usage");
  }

  @Test
  void chatResponseCitationsReuseThePhase2CitationContractExactly() throws Exception {
    ChatResponse response =
        new ChatResponse(
            UUID.randomUUID(), "answer", List.of(CITATION), "hybrid", ChatUsage.placeholder());

    JsonNode citations =
        objectMapper.readTree(objectMapper.writeValueAsString(response)).get("citations");

    assertThat(citations.isArray()).isTrue();
    assertThat(citations).hasSize(1);
    assertThat(fieldNames(citations.get(0)))
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
  void chatUsageHasExactlyTheContractFields() throws Exception {
    JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(ChatUsage.placeholder()));

    // Placeholder fields serialize as JSON null but the keys must all be present.
    assertThat(fieldNames(json))
        .containsExactlyInAnyOrder("promptTokens", "completionTokens", "totalTokens", "model");
    assertThat(json.get("totalTokens").isNull()).isTrue();
    assertThat(json.get("model").isNull()).isTrue();
  }

  @Test
  void chatRequestHasExactlyTheContractFields() throws Exception {
    JsonNode json =
        objectMapper.readTree(
            objectMapper.writeValueAsString(
                new ChatRequest("What is a DPO?", UUID.randomUUID(), 5)));

    assertThat(fieldNames(json)).containsExactlyInAnyOrder("question", "conversationId", "topK");
  }

  @Test
  void chatRequestDeserializesOptionalFields() throws Exception {
    UUID conversationId = UUID.randomUUID();
    String body =
        "{\"question\":\"What is a DPO?\",\"conversationId\":\""
            + conversationId
            + "\",\"topK\":7}";

    ChatRequest request = objectMapper.readValue(body, ChatRequest.class);

    assertThat(request.question()).isEqualTo("What is a DPO?");
    assertThat(request.conversationId()).isEqualTo(conversationId);
    assertThat(request.topK()).isEqualTo(7);
  }

  @Test
  void chatRequestAllowsAbsentConversationIdAndTopK() throws Exception {
    ChatRequest request = objectMapper.readValue("{\"question\":\"hi\"}", ChatRequest.class);

    assertThat(request.question()).isEqualTo("hi");
    assertThat(request.conversationId()).isNull();
    assertThat(request.topK()).isNull();
  }

  @Test
  void validationRejectsNullBlankAndWhitespaceQuestions() {
    for (ChatRequest invalid :
        new ChatRequest[] {
          null,
          new ChatRequest(null, null, null),
          new ChatRequest("", null, null),
          new ChatRequest("   \t\n ", null, null)
        }) {
      Optional<ApiError> error = ChatRequestValidation.validate(invalid);
      assertThat(error).isPresent();
      assertThat(error.get().error()).isEqualTo("invalid_question");
    }
  }

  @Test
  void validationAcceptsANonBlankQuestion() {
    assertThat(ChatRequestValidation.validate(new ChatRequest("What is a DPO?"))).isEmpty();
  }

  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }
}
