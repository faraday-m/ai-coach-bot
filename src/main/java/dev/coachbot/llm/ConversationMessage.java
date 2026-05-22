package dev.coachbot.llm;

import java.util.List;

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
     *
     * @param toolCallId  The ID of the {@link ToolCall} this result corresponds to.
     * @param toolName    The tool name (for human readability in logs).
     * @param resultJson  The tool's output serialised as JSON.
     */
    public static ConversationMessage toolResult(String toolCallId, String toolName, String resultJson) {
        // Format understood by ClaudeBackend.parseToolResultMessage():
        // {"tool_call_id":"...","tool":"...","result":...}
        String content = "{\"tool_call_id\":\"%s\",\"tool\":\"%s\",\"result\":%s}"
                .formatted(toolCallId, toolName, resultJson);
        return new ConversationMessage(Role.TOOL_RESULT, content);
    }

    /**
     * Creates an ASSISTANT turn that carries serialised tool calls (not plain text).
     * Used by the agent loop to record a tool-use step in conversation history.
     *
     * <p>The content uses the {@code {"__tc":[…]}} format understood by
     * {@code ClaudeBackend.parseAssistantMessage()}.  Other backends receive this
     * as a plain ASSISTANT text message (best-effort — they don't do native tool use).
     *
     * @param calls  The tool calls the LLM requested.
     * @param text   Optional accompanying text (Claude's "thinking"); may be null.
     */
    public static ConversationMessage assistantToolCalls(List<ToolCall> calls, String text) {
        StringBuilder sb = new StringBuilder("{\"__tc\":[");
        for (int i = 0; i < calls.size(); i++) {
            if (i > 0) sb.append(",");
            ToolCall c = calls.get(i);
            sb.append("{\"id\":\"").append(esc(c.toolCallId()))
              .append("\",\"name\":\"").append(esc(c.toolName()))
              .append("\",\"args\":\"").append(esc(c.argumentsJson()))
              .append("\"}");
        }
        sb.append("]");
        if (text != null && !text.isBlank()) {
            sb.append(",\"text\":\"").append(esc(text)).append("\"");
        }
        sb.append("}");
        return new ConversationMessage(Role.ASSISTANT, sb.toString());
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
