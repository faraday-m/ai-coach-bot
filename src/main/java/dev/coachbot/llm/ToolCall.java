package dev.coachbot.llm;

/**
 * A tool call emitted by the LLM as part of its response.
 * Returned inside {@link LlmResponse#toolCalls()} when the model wants to invoke a tool.
 *
 * @param toolCallId    Unique ID assigned by the LLM for this call (needed when returning the result).
 * @param toolName      The name of the tool to call (matches a {@link ToolDefinition#name()}).
 * @param argumentsJson The tool arguments as a JSON string (matching the tool's jsonSchema).
 */
public record ToolCall(String toolCallId, String toolName, String argumentsJson) {}
