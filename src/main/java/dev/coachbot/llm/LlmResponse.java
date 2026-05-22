package dev.coachbot.llm;

import java.util.List;

/**
 * Response from an LLM backend.
 *
 * <p>Use {@link #text(String)} for text-only responses (most backends).
 * When the model requests tool calls, {@link #toolCalls()} is non-empty
 * and {@link #text()} may be null or blank.
 */
public record LlmResponse(String text, List<ToolCall> toolCalls) {

    public LlmResponse {
        if (toolCalls == null) toolCalls = List.of();
    }

    /** Convenience factory for plain text responses without tool calls. */
    public static LlmResponse text(String text) {
        return new LlmResponse(text, List.of());
    }
}
