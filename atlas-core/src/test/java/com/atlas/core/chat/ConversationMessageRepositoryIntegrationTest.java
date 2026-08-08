package com.atlas.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.core.document.Citation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Exercises the conversation/message persistence against a real pgvector/pgvector:pg16
 * Testcontainer: append/read-back round-trips (including the citations JSONB), cascade delete, and
 * seq ordering under sequential appends.
 */
@SpringBootTest
@Testcontainers
class ConversationMessageRepositoryIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @Autowired private ConversationRepository conversationRepository;
  @Autowired private MessageRepository messageRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void createTouchAndExists() {
    Conversation conversation = conversationRepository.create();
    assertThat(conversation.id()).isNotNull();
    assertThat(conversation.createdAt()).isNotNull();
    assertThat(conversation.updatedAt()).isNotNull();

    assertThat(conversationRepository.exists(conversation.id())).isTrue();
    assertThat(conversationRepository.exists(UUID.randomUUID())).isFalse();

    // touch bumps updated_at without error.
    conversationRepository.touch(conversation.id());
  }

  @Test
  void appendsAndReadsBackAUserThenAssistantTurnWithCitationsRoundTripped() {
    UUID conversationId = conversationRepository.create().id();

    ChatMessage user =
        messageRepository.append(conversationId, MessageRole.USER, "What is a DPO?", null);
    List<Citation> citations =
        List.of(
            new Citation(
                "c1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "gdpr.pdf",
                "gdpr.pdf",
                55,
                56,
                "The data protection officer shall …"),
            new Citation(
                "c2",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "gdpr.pdf",
                "gdpr.pdf",
                57,
                57,
                "The controller shall …"));
    ChatMessage assistant =
        messageRepository.append(
            conversationId, MessageRole.ASSISTANT, "A DPO is required when … [c1][c2]", citations);

    assertThat(user.seq()).isEqualTo(1);
    assertThat(user.role()).isEqualTo(MessageRole.USER);
    assertThat(user.citations()).isNull();
    assertThat(assistant.seq()).isEqualTo(2);

    List<ChatMessage> readBack = messageRepository.lastMessages(conversationId, 10);
    assertThat(readBack).hasSize(2);

    ChatMessage readUser = readBack.get(0);
    ChatMessage readAssistant = readBack.get(1);
    assertThat(readUser.role()).isEqualTo(MessageRole.USER);
    assertThat(readUser.content()).isEqualTo("What is a DPO?");
    assertThat(readUser.citations()).isNull();

    assertThat(readAssistant.role()).isEqualTo(MessageRole.ASSISTANT);
    assertThat(readAssistant.content()).isEqualTo("A DPO is required when … [c1][c2]");
    // The Citation list survives the JSONB round-trip byte-for-byte (record equality on all
    // fields).
    assertThat(readAssistant.citations()).isEqualTo(citations);
  }

  @Test
  void sequentialAppendsGetContiguousAscendingSeqOrdering() {
    UUID conversationId = conversationRepository.create().id();

    for (int i = 0; i < 6; i++) {
      messageRepository.append(conversationId, MessageRole.USER, "turn " + i, null);
    }

    List<ChatMessage> all = messageRepository.lastMessages(conversationId, 100);
    assertThat(all).extracting(ChatMessage::seq).containsExactly(1, 2, 3, 4, 5, 6);
    assertThat(all)
        .extracting(ChatMessage::content)
        .containsExactly("turn 0", "turn 1", "turn 2", "turn 3", "turn 4", "turn 5");
  }

  @Test
  void lastMessagesReturnsTheMostRecentNInSeqOrder() {
    UUID conversationId = conversationRepository.create().id();
    for (int i = 1; i <= 5; i++) {
      messageRepository.append(conversationId, MessageRole.USER, "turn " + i, null);
    }

    List<ChatMessage> recent = messageRepository.lastMessages(conversationId, 3);

    // The last 3 by seq, presented chronologically.
    assertThat(recent).extracting(ChatMessage::seq).containsExactly(3, 4, 5);
  }

  @Test
  void seqIsScopedPerConversation() {
    UUID first = conversationRepository.create().id();
    UUID second = conversationRepository.create().id();

    messageRepository.append(first, MessageRole.USER, "a", null);
    ChatMessage secondFirstTurn = messageRepository.append(second, MessageRole.USER, "b", null);

    // A fresh conversation starts its own seq at 1, independent of other conversations.
    assertThat(secondFirstTurn.seq()).isEqualTo(1);
  }

  @Test
  void deletingAConversationCascadesToItsMessages() {
    UUID conversationId = conversationRepository.create().id();
    messageRepository.append(conversationId, MessageRole.USER, "hi", null);
    messageRepository.append(conversationId, MessageRole.ASSISTANT, "hello", List.of());
    assertThat(messageCount(conversationId)).isEqualTo(2);

    jdbcTemplate.update("DELETE FROM conversation WHERE id = ?", conversationId);

    assertThat(messageCount(conversationId)).isZero();
  }

  private int messageCount(UUID conversationId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM message WHERE conversation_id = ?",
            Integer.class,
            conversationId);
    return count != null ? count : 0;
  }
}
