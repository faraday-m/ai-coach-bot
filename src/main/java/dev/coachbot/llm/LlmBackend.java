package dev.coachbot.llm;

import dev.coachbot.plugin.Identifiable;

import java.util.function.Consumer;

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
     * When the request contains {@link LlmRequest#tools()}, backends that
     * {@link #supportsTools()} may return a response with
     * {@link LlmResponse#toolCalls()} populated instead of (or alongside) text.
     *
     * <p>Implementations must be thread-safe — multiple {@link dev.coachbot.core.GroupSession}s
     * may call this concurrently.
     */
    LlmResponse complete(LlmRequest request);

    /**
     * Returns {@code true} if this backend supports native tool use and will populate
     * {@link LlmResponse#toolCalls()} when tools are passed in the request.
     *
     * <p>Backends that return {@code false} (the default) silently ignore any tools
     * passed in {@link LlmRequest#tools()} and always return text-only responses.
     */
    default boolean supportsTools() { return false; }

    /**
     * Streams tokens from the LLM, invoking {@code tokenSink} for each chunk.
     *
     * <p>The default implementation falls back to a single {@link #complete(LlmRequest)}
     * call and delivers the whole text as one chunk — sufficient for non-streaming
     * backends and for code that doesn't need token-by-token delivery.
     *
     * <p>Backends that support streaming (currently {@code ClaudeBackend}) override
     * this with a proper streaming implementation.
     */
    default void stream(LlmRequest request, Consumer<String> tokenSink) {
        String text = complete(request).text();
        if (text != null) tokenSink.accept(text);
    }
}
