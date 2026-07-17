package com.atlas.core.document;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
class IngestionExecutorConfig {

  static final String EXECUTOR_BEAN_NAME = "ingestionExecutor";

  @Bean(name = EXECUTOR_BEAN_NAME)
  Executor ingestionExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("ingestion-");
    // Backpressure over rejection: under sustained overload, run the task on the publishing
    // thread rather than dropping a document's ingestion silently.
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.initialize();
    return executor;
  }
}
