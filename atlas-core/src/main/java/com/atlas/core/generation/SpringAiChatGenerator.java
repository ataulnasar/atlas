package com.atlas.core.generation;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;

/**
 * Wraps a Spring AI {@link OpenAiChatModel}. The model is built with its default options (model id
 * and, where the family allows it, temperature) already baked in by {@link GenerationConfig}, so a
 * call just supplies the two messages. A plain class rather than a Spring bean — see {@link
 * GenerationConfig} for why — which also makes it unit-testable against a stub model.
 */
class SpringAiChatGenerator implements ChatGenerator {

  private final OpenAiChatModel chatModel;
  private final String configuredModel;

  SpringAiChatGenerator(OpenAiChatModel chatModel, String configuredModel) {
    this.chatModel = chatModel;
    this.configuredModel = configuredModel;
  }

  @Override
  public GenerationResult generate(String systemPrompt, String userPrompt) {
    try {
      ChatResponse response = chatModel.call(promptOf(systemPrompt, userPrompt));
      return toResult(response);
    } catch (Exception e) {
      // Never leak the provider payload: wrap with a generic message. The cause carries the detail
      // and is logged server-side; callers get a clean 502.
      throw new GenerationException("Chat generation failed", e);
    }
  }

  @Override
  public GenerationResult generateStreaming(
      String systemPrompt, String userPrompt, Consumer<String> onToken) {
    StringBuilder answer = new StringBuilder();
    Integer promptTokens = null;
    Integer completionTokens = null;
    Integer totalTokens = null;
    String model = configuredModel;
    try {
      // Consume the reactive stream blocking-style on this (virtual) thread — no WebFlux, no
      // scheduler. toStream()'s close() cancels the upstream subscription if we stop early or
      // throw.
      try (Stream<ChatResponse> chunks =
          chatModel.stream(promptOf(systemPrompt, userPrompt)).toStream()) {
        for (ChatResponse chunk : (Iterable<ChatResponse>) chunks::iterator) {
          Generation result = chunk.getResult();
          if (result != null && result.getOutput() != null) {
            String delta = result.getOutput().getText();
            if (delta != null && !delta.isEmpty()) {
              answer.append(delta);
              onToken.accept(delta);
            }
          }
          // OpenAI reports usage on a trailing chunk (and echoes the model id); keep the latest.
          ChatResponseMetadata metadata = chunk.getMetadata();
          if (metadata != null) {
            if (metadata.getModel() != null && !metadata.getModel().isBlank()) {
              model = metadata.getModel();
            }
            Usage usage = metadata.getUsage();
            if (usage != null && usage.getTotalTokens() != null) {
              promptTokens = usage.getPromptTokens();
              completionTokens = usage.getCompletionTokens();
              totalTokens = usage.getTotalTokens();
            }
          }
        }
      }
      return new GenerationResult(
          answer.toString(), promptTokens, completionTokens, totalTokens, model);
    } catch (Exception e) {
      throw new GenerationException("Chat generation failed", e);
    }
  }

  private static Prompt promptOf(String systemPrompt, String userPrompt) {
    return new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)));
  }

  private GenerationResult toResult(ChatResponse response) {
    Generation result = response.getResult();
    String text = result != null && result.getOutput() != null ? result.getOutput().getText() : "";

    Integer promptTokens = null;
    Integer completionTokens = null;
    Integer totalTokens = null;
    String model = configuredModel;

    ChatResponseMetadata metadata = response.getMetadata();
    if (metadata != null) {
      if (metadata.getModel() != null && !metadata.getModel().isBlank()) {
        model = metadata.getModel();
      }
      Usage usage = metadata.getUsage();
      if (usage != null) {
        promptTokens = usage.getPromptTokens();
        completionTokens = usage.getCompletionTokens();
        totalTokens = usage.getTotalTokens();
      }
    }

    return new GenerationResult(text, promptTokens, completionTokens, totalTokens, model);
  }
}
