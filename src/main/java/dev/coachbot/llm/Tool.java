package dev.coachbot.llm;

/**
 * SPI for tools callable by the LLM during an agentic loop.
 *
 * <p>Implementations are plain objects (not Spring beans) — they are constructed
 * by {@link dev.coachbot.core.GroupSession}, which already holds the resources
 * (storage backend, etc.) the tools need.
 *
 * <p>Each tool must be thread-safe; the agent loop may call {@link #execute} from
 * different virtual threads.
 */
public interface Tool {

    /** Unique name sent to the LLM. Use snake_case (e.g. {@code write_file}). */
    String name();

    /** Human-readable description that helps the LLM decide when to call this tool. */
    String description();

    /**
     * JSON Schema string (object schema) describing the tool's input parameters.
     * Example:
     * <pre>
     * {"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}
     * </pre>
     */
    String jsonSchema();

    /**
     * Execute the tool with the provided JSON arguments.
     *
     * @param toolCallId  The call ID assigned by the LLM (passed back in the result).
     * @param argsJson    Arguments as a JSON object string matching {@link #jsonSchema()}.
     * @return A {@link ToolResult} — never throws; errors are wrapped in the result.
     */
    ToolResult execute(String toolCallId, String argsJson);

    /** Convenience: builds a {@link ToolDefinition} from this tool's metadata. */
    default ToolDefinition definition() {
        return new ToolDefinition(name(), description(), jsonSchema());
    }
}
