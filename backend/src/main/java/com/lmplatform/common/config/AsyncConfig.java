package com.lmplatform.common.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

/** Isolates best-effort background work from request and model-streaming threads. */
@Configuration
@EnableAsync
public class AsyncConfig {

    /** Creates a bounded virtual-thread executor dedicated to title generation. */
    @Bean("titleTaskExecutor")
    Executor titleTaskExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("title-generator-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(4);
        return executor;
    }
}
