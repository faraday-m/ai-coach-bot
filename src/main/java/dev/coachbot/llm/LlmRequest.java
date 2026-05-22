package dev.coachbot.llm;

import java.util.List;

/**
 * Input to an LLM backend. The backend is responsible for converting this
 * to its native API format (e.g. Anthropic messages array, Ollama chat request).
 *
 * <p>Use the {@link #of} factory for requests without tools (most callers).
 * Pass a non-empty {@code tools} list to enable agentic tool use.
 */
public record LlmRequest(
        String systemPrompt,
        List<ConversationMessage> history,   // previous turns, oldest first
        String userMessage,                  // current user input
        String userId,
        String agentId,
        List<ToolDefinition> tools           // empty = no tool use
) {
    public LlmRequest {
        if (history == null) history = List.of();
        if (tools   == null) tools   = List.of();
    }

    /** Convenience factory for requests that do not use tools. */
    public static LlmRequest of(String systemPrompt, List<ConversationMessage> history,
                                String userMessage, String userId, String agentId) {
        return new LlmRequest(systemPrompt, history, userMessage, userId, agentId, List.of());
    }
}
