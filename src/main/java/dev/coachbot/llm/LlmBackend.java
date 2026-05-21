package dev.coachbot.llm;

import dev.coachbot.plugin.Identifiable;

/**
 * SPI for LLM backends (Claude, Ollama, OpenAI-compatible, …).
 *
 * <p>To add a new backend:
 * <ol>
 *   <li>Implement this interface.</li>
 *   <li>Annotate with {@code @Component} (+ {@code @ConditionalOnProperty} if optional).</li>
 *   <li>Reference the backend by {@link #id()} in {@code application.yml} seeds or agent config.</li>
 * </ol>
 */
public interface LlmBackend extends Identifiable {

    /**
     * Sends the conversation to the LLM and returns the response synchronously.
     * Implementations should be thread-safe — multiple {@link dev.coachbot.core.GroupSession}s
     * may call this concurrently.
     */
    LlmResponse complete(LlmRequest request);
}
