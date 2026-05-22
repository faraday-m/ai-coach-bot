package dev.coachbot.llm;

/**
 * A single turn in a conversation, transport- and LLM-agnostic.
 * Each {@link dev.coachbot.core.GroupSession} keeps per-user history as a list of these.
 */
public record ConversationMessage(Role role, String content) {

    public enum Role { USER, ASSISTANT, TOOL_RESULT }

    public static ConversationMessage user(String content) {
        return new ConversationMessage(Role.USER, content);
    }

    public static ConversationMessage assistant(String content) {
        return new ConversationMessage(Role.ASSISTANT, content);
    }

    /**
     * Creates a tool-result turn to be injected into history after a tool call.
     * The content is a JSON string (the tool's return value).
     *
     * @param toolCallId  The ID of the {@link ToolCall} this result corresponds to.
     * @param toolName    The tool name (for human readability in logs).
     * @param resultJson  The tool's output serialised as JSON.
     */
    public static ConversationMessage toolResult(String toolCallId, String toolName, String resultJson) {
        // Encode tool call id and name in the content for backends that need it.
        // Format: {"tool_call_id":"...","tool":"...","result":...}
        String content = "{\"tool_call_id\":\"%s\",\"tool\":\"%s\",\"result\":%s}"
                .formatted(toolCallId, toolName, resultJson);
        return new ConversationMessage(Role.TOOL_RESULT, content);
    }
}
