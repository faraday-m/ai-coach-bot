package dev.coachbot.llm;

import java.util.List;

/**
 * Input to an LLM backend. The backend is responsible for converting this
 * to its native API format (e.g. Anthropic messages array, Ollama chat request).
 */
public record LlmRequest(
        String systemPrompt,
        List<ConversationMessage> history,   // previous turns, oldest first
        String userMessage,                  // current user input
        String userId,
        String agentId
) {
    public LlmRequest {
        if (history == null) history = List.of();
    }
}
