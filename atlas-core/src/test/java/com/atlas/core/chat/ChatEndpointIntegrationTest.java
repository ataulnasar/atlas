package com.atlas.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.core.document.ApiError;
import com.atlas.core.document.Citation;
import com.atlas.core.embedding.EmbeddingService;
import com.atlas.core.embedding.VectorLiteral;
import com.atlas.core.generation.FakeChatGenerator;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end chat over the full RAG loop against a real Postgres/pgvector container, with a fake
 * embedding service (fixed query vector, so seeded chunks' cosine to it fully controls vector
 * ranks) and a fake chat generator returning canned answers with citation markers. No API key, no
 * network.
 *
 * <p>The two seeded chunks are ranked purely by vector similarity: the questions used share no term
 * with the chunk text, so the keyword leg is empty and c1/c2 map deterministically to the higher-
 * and lower-cosine chunk.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ChatEndpointIntegrationTest.ChatTestConfig.class)
@Testcontainers
class ChatEndpointIntegrationTest {

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

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MessageRepository messageRepository;
  @Autowired private FakeChatGenerator chatGenerator;

  @BeforeEach
  void reset() {
    jdbcTemplate.execute("TRUNCATE document CASCADE");
    jdbcTemplate.execute("TRUNCATE conversation CASCADE");
    chatGenerator.setFailing(false);
  }

  @Test
  void happyPathReturnsRenumberedCitationsAndPersistsBothMessages() {
    UUID documentId = seedReadyDocument("policy.txt");
    UUID chunkA =
        seedChunk(documentId, 0, "The controller shall appoint a data protection officer.", 0.99);
    UUID chunkB =
        seedChunk(documentId, 1, "Records of processing activities must be maintained.", 0.90);

    // Offered c1=chunkA (cosine .99), c2=chunkB (cosine .90). The model cites them out of order.
    chatGenerator.setAnswer("The controller appoints a DPO [c2][c1].");

    ResponseEntity<ChatResponse> response =
        restTemplate.postForEntity(
            "/api/chat", new ChatRequest("Who is accountable?"), ChatResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    ChatResponse body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.conversationId()).isNotNull();
    assertThat(body.retrievalMode()).isEqualTo("hybrid");

    // First-appearance renumbering: cited c2 then c1 -> becomes c1 then c2.
    assertThat(body.answer()).isEqualTo("The controller appoints a DPO [c1][c2].");
    assertThat(body.citations()).extracting(Citation::citationId).containsExactly("c1", "c2");
    assertThat(body.citations().get(0).chunkId()).isEqualTo(chunkB);
    assertThat(body.citations().get(1).chunkId()).isEqualTo(chunkA);

    assertThat(body.usage().promptTokens()).isEqualTo(FakeChatGenerator.PROMPT_TOKENS);
    assertThat(body.usage().completionTokens()).isEqualTo(FakeChatGenerator.COMPLETION_TOKENS);
    assertThat(body.usage().totalTokens()).isEqualTo(FakeChatGenerator.TOTAL_TOKENS);
    assertThat(body.usage().model()).isEqualTo(FakeChatGenerator.MODEL);

    // Both messages persisted, in order, with citations JSONB on the assistant turn only.
    List<ChatMessage> messages = messageRepository.lastMessages(body.conversationId(), 10);
    assertThat(messages).hasSize(2);

    ChatMessage user = messages.get(0);
    assertThat(user.seq()).isEqualTo(1);
    assertThat(user.role()).isEqualTo(MessageRole.USER);
    assertThat(user.content()).isEqualTo("Who is accountable?");
    assertThat(user.citations()).isNull();

    ChatMessage assistant = messages.get(1);
    assertThat(assistant.seq()).isEqualTo(2);
    assertThat(assistant.role()).isEqualTo(MessageRole.ASSISTANT);
    assertThat(assistant.content()).isEqualTo("The controller appoints a DPO [c1][c2].");
    assertThat(assistant.citations()).extracting(Citation::chunkId).containsExactly(chunkB, chunkA);
  }

  @Test
  void secondTurnReplaysHistoryInTheAssembledPrompt() {
    UUID documentId = seedReadyDocument("policy.txt");
    seedChunk(documentId, 0, "The controller shall appoint a data protection officer.", 0.99);

    chatGenerator.setAnswer("A DPO is required [c1].");
    ChatResponse first =
        restTemplate
            .postForEntity("/api/chat", new ChatRequest("Who is accountable?"), ChatResponse.class)
            .getBody();
    assertThat(first).isNotNull();

    chatGenerator.setAnswer("Yes, exactly [c1].");
    ResponseEntity<ChatResponse> second =
        restTemplate.postForEntity(
            "/api/chat",
            new ChatRequest("Is that mandatory?", first.conversationId(), null),
            ChatResponse.class);

    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

    // The second turn's user prompt must carry the prior turn verbatim, then this turn's question.
    String prompt = chatGenerator.lastUserPrompt();
    assertThat(prompt).contains("Previous conversation:");
    assertThat(prompt).contains("User: Who is accountable?");
    assertThat(prompt).contains("Assistant: A DPO is required [c1].");
    assertThat(prompt).contains("Question: Is that mandatory?");

    // Four messages now: two per turn, contiguous seq.
    assertThat(messageRepository.lastMessages(first.conversationId(), 10))
        .extracting(ChatMessage::seq)
        .containsExactly(1, 2, 3, 4);
  }

  @Test
  void abstentionAnswerPersistsWithEmptyCitations() {
    UUID documentId = seedReadyDocument("policy.txt");
    seedChunk(documentId, 0, "Unrelated boilerplate about retention schedules.", 0.5);

    chatGenerator.setAnswer("The provided documents do not cover this question.");

    ChatResponse body =
        restTemplate
            .postForEntity(
                "/api/chat", new ChatRequest("What is the capital of France?"), ChatResponse.class)
            .getBody();

    assertThat(body).isNotNull();
    assertThat(body.answer()).isEqualTo("The provided documents do not cover this question.");
    assertThat(body.citations()).isEmpty();

    List<ChatMessage> messages = messageRepository.lastMessages(body.conversationId(), 10);
    assertThat(messages).hasSize(2);
    ChatMessage assistant = messages.get(1);
    assertThat(assistant.role()).isEqualTo(MessageRole.ASSISTANT);
    // Empty (not null) citations: an assistant turn always serializes its list, even when empty.
    assertThat(assistant.citations()).isNotNull().isEmpty();
  }

  @Test
  void unknownConversationIdIs404AndPersistsNothing() {
    ResponseEntity<ApiError> response =
        restTemplate.postForEntity(
            "/api/chat", new ChatRequest("Continue?", UUID.randomUUID(), null), ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().error()).isEqualTo("conversation_not_found");

    assertThat(count("conversation")).isZero();
    assertThat(count("message")).isZero();
  }

  @Test
  void generationFailureIs502AndRollsBackBothMessages() {
    UUID documentId = seedReadyDocument("policy.txt");
    seedChunk(documentId, 0, "The controller shall appoint a data protection officer.", 0.99);
    chatGenerator.setFailing(true);

    ResponseEntity<ApiError> response =
        restTemplate.postForEntity(
            "/api/chat", new ChatRequest("Who is accountable?"), ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().error()).isEqualTo("generation_failed");
    // A clean body — no provider payload leaks.
    assertThat(response.getBody().message()).doesNotContain("fake provider is down");

    // Rollback: neither the user turn nor a new conversation is left behind.
    assertThat(count("message")).isZero();
    assertThat(count("conversation")).isZero();
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

  /** Seeds a chunk whose embedding has the given cosine to the fixed query vector Q. */
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

  // Q = [1, 0, 0, ...]; an embedding [c, sqrt(1-c^2), 0, ...] has unit norm and cosine c to Q.
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

  @TestConfiguration
  static class ChatTestConfig {

    /**
     * Registered as the concrete type so a test can configure it, and as ChatGenerator for wiring.
     */
    @Bean
    FakeChatGenerator chatGenerator() {
      return new FakeChatGenerator();
    }

    @Bean
    EmbeddingService embeddingService() {
      // Always returns the fixed query vector Q — the query embedding is the only thing embedded at
      // chat time, so fixing it makes vector ranks a function of the seeded chunks' cosine to Q.
      return texts -> texts.stream().map(text -> fixedQueryVector()).toList();
    }
  }
}
