package com.atlas.core.chat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Executor for SSE streaming chat tasks. {@code SseEmitter} needs the request thread released and
 * the work run elsewhere; each streamed answer is one blocking task that mostly waits on the model,
 * so a virtual-thread-per-task executor fits exactly — cheap to spawn one per open stream, blocking
 * style, no WebFlux (ADR 0001). Closed on shutdown so in-flight streams are not orphaned.
 */
@Configuration
class ChatStreamConfig {

  static final String STREAM_EXECUTOR_BEAN = "chatStreamExecutor";

  @Bean(name = STREAM_EXECUTOR_BEAN, destroyMethod = "close")
  ExecutorService chatStreamExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}
