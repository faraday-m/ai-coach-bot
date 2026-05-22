package dev.coachbot.llm;

/**
 * Describes a tool that an LLM can call.
 *
 * @param name        Tool name (must be unique within a request; use snake_case).
 * @param description Human-readable description used by the LLM to decide when to call this tool.
 * @param jsonSchema  JSON Schema object string describing the tool's input parameters.
 *                    Example: {@code {"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}}
 */
public record ToolDefinition(String name, String description, String jsonSchema) {}
