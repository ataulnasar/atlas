package com.atlas.core.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Offline tests for prompt resource loading and user-message assembly. */
class PromptTemplateTest {

  private PromptTemplate template(int historyTurns) {
    return new PromptTemplate(new GenerationProperties("gpt-5-mini", 0.1, 6000, historyTurns));
  }

  @Test
  void systemPromptLoadsAndCarriesTheGroundingRulesIncludingAbstentionVerbatim() {
    String system = template(3).system();

    assertThat(system).contains(PromptTemplate.ABSTENTION_INSTRUCTION);
    assertThat(system).contains("ONLY"); // answer only from the sources
    assertThat(system).contains("[cN]"); // inline citation marker instruction
    assertThat(system).containsIgnoringCase("same language as the question");
    // Abstaining must not leak citations: declining replies carry no [cN] markers.
    assertThat(system).contains("do not include any [cN] citation markers");
  }

  @Test
  void userMessageWithoutHistoryIsSourcesThenQuestion() {
    String user = template(3).user(List.of(), "SRC", "What is a DPO?");

    assertThat(user).isEqualTo("Sources:\nSRC\n\nQuestion: What is a DPO?");
  }

  @Test
  void userMessageWithHistoryPrependsTheConversationTailVerbatim() {
    String user = template(3).user(List.of(new QaPair("Q1", "A1")), "SRC", "Q2?");

    assertThat(user)
        .isEqualTo(
            "Previous conversation:\n"
                + "User: Q1\n"
                + "Assistant: A1\n"
                + "\n"
                + "Sources:\n"
                + "SRC\n"
                + "\n"
                + "Question: Q2?");
  }

  @Test
  void historyIsTrimmedToTheLastNPairsOldestFirst() {
    String user =
        template(2)
            .user(
                List.of(new QaPair("Q1", "A1"), new QaPair("Q2", "A2"), new QaPair("Q3", "A3")),
                "SRC",
                "Q4?");

    // Only the last two pairs survive, oldest of those first; Q1/A1 dropped.
    assertThat(user)
        .isEqualTo(
            "Previous conversation:\n"
                + "User: Q2\n"
                + "Assistant: A2\n"
                + "\n"
                + "User: Q3\n"
                + "Assistant: A3\n"
                + "\n"
                + "Sources:\n"
                + "SRC\n"
                + "\n"
                + "Question: Q4?");
  }

  @Test
  void historyTurnsZeroSuppressesTheConversationTail() {
    String user = template(0).user(List.of(new QaPair("Q1", "A1")), "SRC", "Q2?");

    assertThat(user).isEqualTo("Sources:\nSRC\n\nQuestion: Q2?");
  }
}
