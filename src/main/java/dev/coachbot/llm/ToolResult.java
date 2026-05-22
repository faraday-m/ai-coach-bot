package dev.coachbot.llm;

/**
 * Result of executing a {@link ToolCall}, to be fed back to the LLM as context
 * in the next iteration of the agent loop.
 *
 * @param toolCallId  Matches the {@link ToolCall#toolCallId()} this result answers.
 * @param toolName    The tool that was called (for logging / history).
 * @param resultJson  The tool's output as a JSON string.
 * @param isError     {@code true} if the tool threw an exception.
 */
public record ToolResult(String toolCallId, String toolName, String resultJson, boolean isError) {}
