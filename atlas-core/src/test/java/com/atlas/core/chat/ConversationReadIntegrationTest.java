package com.atlas.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.core.document.ApiError;
import com.atlas.core.document.Citation;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The conversation read-back endpoint. Seeds a turn directly through the repositories (the write
 * path is exercised elsewhere), then reads it via {@code GET /api/conversations/{id}}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ConversationReadIntegrationTest {

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
  @Autowired private ConversationRepository conversationRepository;
  @Autowired private MessageRepository messageRepository;

  @Test
  void readsBackTheOrderedTurnsOfAConversation() {
    UUID conversationId = conversationRepository.create().id();
    messageRepository.append(
        conversationId, MessageRole.USER, "What are the tasks of the DPO?", null);
    Citation citation =
        new Citation(
            "c1", UUID.randomUUID(), UUID.randomUUID(), "gdpr.pdf", "gdpr.pdf", 55, 56, "…");
    messageRepository.append(
        conversationId, MessageRole.ASSISTANT, "The DPO shall … [c1]", List.of(citation));

    ResponseEntity<ConversationResponse> response =
        restTemplate.getForEntity(
            "/api/conversations/" + conversationId, ConversationResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    ConversationResponse body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.conversationId()).isEqualTo(conversationId);
    assertThat(body.messages()).hasSize(2);

    ConversationMessageView user = body.messages().get(0);
    assertThat(user.role()).isEqualTo("user");
    assertThat(user.content()).isEqualTo("What are the tasks of the DPO?");
    assertThat(user.citations()).isNull();
    assertThat(user.createdAt()).isNotNull();

    ConversationMessageView assistant = body.messages().get(1);
    assertThat(assistant.role()).isEqualTo("assistant");
    assertThat(assistant.content()).isEqualTo("The DPO shall … [c1]");
    assertThat(assistant.citations()).extracting(Citation::citationId).containsExactly("c1");
    assertThat(assistant.citations().get(0).chunkId()).isEqualTo(citation.chunkId());
  }

  @Test
  void unknownConversationIdIs404() {
    ResponseEntity<ApiError> response =
        restTemplate.getForEntity("/api/conversations/" + UUID.randomUUID(), ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().error()).isEqualTo("conversation_not_found");
  }
}
