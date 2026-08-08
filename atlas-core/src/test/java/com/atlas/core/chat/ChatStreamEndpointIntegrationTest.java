package com.atlas.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.core.document.Citation;
import com.atlas.core.embedding.EmbeddingService;
import com.atlas.core.embedding.VectorLiteral;
import com.atlas.core.generation.FakeChatGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end SSE streaming chat against a real Postgres/pgvector container, with a fake embedding
 * service (fixed query vector) and a fake streaming generator emitting scripted deltas. The full
 * SSE response is finite here (the fake completes the stream), so the test reads the whole body and
 * parses the {@code event:}/{@code data:} frames.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ChatStreamEndpointIntegrationTest.ChatTestConfig.class)
@Testcontainers
class ChatStreamEndpointIntegrationTest {

  private static final int DIMENSIONS = 1536;

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @TempDir static Path STORAGE_ROOT;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("atlas.storage.path", () -> STORAGE_ROOT.toString());
  }

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MessageRepository messageRepository;
  @Autowired private FakeChatGenerator chatGenerator;
  @Autowired private ObjectMapper objectMapper;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @BeforeEach
  void reset() {
    jdbcTemplate.execute("TRUNCATE document CASCADE");
    jdbcTemplate.execute("TRUNCATE conversation CASCADE");
    chatGenerator.setFailing(false);
    chatGenerator.setScriptedDeltas(null);
    chatGenerator.setFailAfterDeltas(-1);
  }

  @Test
  void streamsTokensThenCitationsThenDoneWithRenumberedAnswer() throws Exception {
    UUID documentId = seedReadyDocument("policy.txt");
    UUID chunkA =
        seedChunk(documentId, 0, "The controller shall appoint a data protection officer.", 0.99);
    UUID chunkB =
        seedChunk(documentId, 1, "Records of processing activities must be maintained.", 0.90);

    // Offered c1=chunkA (.99), c2=chunkB (.90). The model streams them cited out of order.
    chatGenerator.setScriptedDeltas(
        List.of("The ", "controller ", "appoints ", "a ", "DPO ", "[c2]", "[c1]", "."));

    List<SseEvent> events = postStream(new ChatRequest("Who is accountable?"));

    // Event order: every token first, then exactly one citations, then exactly one done.
    List<String> names = events.stream().map(SseEvent::event).toList();
    assertThat(names)
        .containsExactly(
            "token",
            "token",
            "token",
            "token",
            "token",
            "token",
            "token",
            "token",
            "citations",
            "done");

    // The streamed deltas concatenate to the RAW answer, with the model's original [cN] markers.
    String streamed =
        events.stream()
            .filter(e -> e.event().equals("token"))
            .map(e -> readJson(e.data()).get("delta").asText())
            .reduce("", String::concat);
    assertThat(streamed).isEqualTo("The controller appoints a DPO [c2][c1].");

    // The citations event carries the reconciled answer: markers renumbered to first-appearance
    // order (c2 -> c1, c1 -> c2) and the cited subset in that same order.
    JsonNode citationsEvent = readJson(dataOf(events, "citations"));
    assertThat(citationsEvent.get("answer").asText())
        .isEqualTo("The controller appoints a DPO [c1][c2].");
    List<Citation> citations =
        objectMapper.convertValue(
            citationsEvent.get("citations"),
            objectMapper.getTypeFactory().constructCollectionType(List.class, Citation.class));
    assertThat(citations).extracting(Citation::citationId).containsExactly("c1", "c2");
    assertThat(citations.get(0).chunkId()).isEqualTo(chunkB);
    assertThat(citations.get(1).chunkId()).isEqualTo(chunkA);

    // The done event carries usage + conversationId + retrievalMode. Usage propagation is the
    // token-count contract the streaming UI reads: the full ChatUsage the generator reported must
    // survive to the done payload (regression guard for the "0 tokens" streaming bug).
    JsonNode doneEvent = readJson(dataOf(events, "done"));
    UUID conversationId = UUID.fromString(doneEvent.get("conversationId").asText());
    assertThat(doneEvent.get("retrievalMode").asText()).isEqualTo("hybrid");
    JsonNode usage = doneEvent.get("usage");
    assertThat(usage.get("promptTokens").asInt()).isEqualTo(FakeChatGenerator.PROMPT_TOKENS);
    assertThat(usage.get("completionTokens").asInt())
        .isEqualTo(FakeChatGenerator.COMPLETION_TOKENS);
    assertThat(usage.get("totalTokens").asInt()).isEqualTo(FakeChatGenerator.TOTAL_TOKENS);
    assertThat(usage.get("model").asText()).isEqualTo(FakeChatGenerator.MODEL);

    // Persisted once, after the full answer assembled: the renumbered answer + citations JSONB.
    List<ChatMessage> messages = messageRepository.lastMessages(conversationId, 10);
    assertThat(messages).hasSize(2);
    assertThat(messages.get(0).role()).isEqualTo(MessageRole.USER);
    assertThat(messages.get(0).content()).isEqualTo("Who is accountable?");
    ChatMessage assistant = messages.get(1);
    assertThat(assistant.content()).isEqualTo("The controller appoints a DPO [c1][c2].");
    assertThat(assistant.citations()).extracting(Citation::chunkId).containsExactly(chunkB, chunkA);
  }

  @Test
  void renumberedAnswerInCitationsEventMatchesTheSyncEndpointExtractorOutput() throws Exception {
    UUID documentId = seedReadyDocument("policy.txt");
    seedChunk(documentId, 0, "The controller shall appoint a data protection officer.", 0.99);
    seedChunk(documentId, 1, "Records of processing activities must be maintained.", 0.90);

    // Cite only the second offered source: extraction must renumber c2 -> c1 and return one source.
    chatGenerator.setScriptedDeltas(List.of("Records must be kept ", "[c2]", "."));

    List<SseEvent> events = postStream(new ChatRequest("What about records?"));

    JsonNode citationsEvent = readJson(dataOf(events, "citations"));
    assertThat(citationsEvent.get("answer").asText()).isEqualTo("Records must be kept [c1].");
    assertThat(citationsEvent.get("citations")).hasSize(1);
    assertThat(citationsEvent.get("citations").get(0).get("citationId").asText()).isEqualTo("c1");
  }

  @Test
  void midStreamFailureEmitsErrorEventAndPersistsNothing() throws Exception {
    UUID documentId = seedReadyDocument("policy.txt");
    seedChunk(documentId, 0, "The controller shall appoint a data protection officer.", 0.99);

    chatGenerator.setScriptedDeltas(List.of("The ", "controller ", "appoints ", "a ", "DPO."));
    chatGenerator.setFailAfterDeltas(2); // two tokens, then the provider drops.

    List<SseEvent> events = postStream(new ChatRequest("Who is accountable?"));

    List<String> names = events.stream().map(SseEvent::event).toList();
    // Two token events, then a terminal error — no citations, no done.
    assertThat(names).containsExactly("token", "token", "error");
    JsonNode error = readJson(dataOf(events, "error"));
    assertThat(error.get("error").asText()).isEqualTo("generation_failed");
    assertThat(error.get("message").asText()).doesNotContain("dropped mid-stream");

    // A mid-stream failure persists nothing (finish() never ran).
    assertThat(count("message")).isZero();
    assertThat(count("conversation")).isZero();
  }

  private List<SseEvent> postStream(ChatRequest request) throws Exception {
    HttpRequest httpRequest =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/chat/stream"))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
            .build();
    HttpResponse<String> response =
        httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return parse(response.body());
  }

  // Minimal SSE frame parser: frames are blank-line separated; each has an event: and a data: line.
  private static List<SseEvent> parse(String body) {
    List<SseEvent> events = new ArrayList<>();
    for (String frame : body.split("\n\n")) {
      if (frame.isBlank()) {
        continue;
      }
      String name = null;
      StringBuilder data = new StringBuilder();
      for (String line : frame.split("\n")) {
        if (line.startsWith("event:")) {
          name = line.substring("event:".length()).trim();
        } else if (line.startsWith("data:")) {
          data.append(line.substring("data:".length()).stripLeading());
        }
      }
      events.add(new SseEvent(name, data.toString()));
    }
    return events;
  }

  private static String dataOf(List<SseEvent> events, String eventName) {
    return events.stream()
        .filter(e -> eventName.equals(e.event()))
        .map(SseEvent::data)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no '" + eventName + "' event in stream"));
  }

  private JsonNode readJson(String json) {
    try {
      return objectMapper.readTree(json);
    } catch (Exception e) {
      throw new AssertionError("bad JSON in SSE data: " + json, e);
    }
  }

  private int count(String table) {
    Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    return count != null ? count : 0;
  }

  private UUID seedReadyDocument(String filename) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO document (filename, content_hash, status) "
            + "VALUES (?, ?, 'READY') RETURNING id",
        UUID.class,
        filename,
        "hash-" + UUID.randomUUID());
  }

  private UUID seedChunk(UUID documentId, int chunkIndex, String content, double cosineToQuery) {
    String literal = VectorLiteral.format(embeddingWithCosine(cosineToQuery));
    return jdbcTemplate.queryForObject(
        "INSERT INTO chunk (document_id, chunk_index, content, start_page, end_page, token_count, "
            + "embedding) VALUES (?, ?, ?, 1, 1, 12, CAST(? AS vector)) RETURNING id",
        UUID.class,
        documentId,
        chunkIndex,
        content,
        literal);
  }

  private static float[] embeddingWithCosine(double cosine) {
    float[] vector = new float[DIMENSIONS];
    vector[0] = (float) cosine;
    vector[1] = (float) Math.sqrt(1 - cosine * cosine);
    return vector;
  }

  private static float[] fixedQueryVector() {
    float[] vector = new float[DIMENSIONS];
    vector[0] = 1f;
    return vector;
  }

  private record SseEvent(String event, String data) {}

  @TestConfiguration
  static class ChatTestConfig {

    @Bean
    FakeChatGenerator chatGenerator() {
      return new FakeChatGenerator();
    }

    @Bean
    EmbeddingService embeddingService() {
      return texts -> texts.stream().map(text -> fixedQueryVector()).toList();
    }
  }
}
