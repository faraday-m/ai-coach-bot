package dev.coachbot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.coachbot.llm.Tool;
import dev.coachbot.llm.ToolResult;
import dev.coachbot.storage.StorageBackend;

/**
 * Tool that writes (or overwrites) a file in the agent's storage backend.
 * The LLM can call this to save notes, articles, or any structured content.
 */
public class WriteFileTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StorageBackend storage;

    public WriteFileTool(StorageBackend storage) {
        this.storage = storage;
    }

    @Override
    public String name() { return "write_file"; }

    @Override
    public String description() {
        return "Write or overwrite a file in storage. Use this to save notes, summaries, or articles. " +
               "The path should include a folder and file name with .md extension.";
    }

    @Override
    public String jsonSchema() {
        return """
                {"type":"object","properties":\
                {"path":{"type":"string","description":"File path relative to storage root, e.g. notes/gc-algorithms.md"},\
                "content":{"type":"string","description":"Full file content to write"}},\
                "required":["path","content"]}""";
    }

    @Override
    public ToolResult execute(String toolCallId, String argsJson) {
        try {
            JsonNode args = MAPPER.readTree(argsJson);
            String path    = args.get("path").asText();
            String content = args.get("content").asText();
            storage.write(path, content);
            return new ToolResult(toolCallId, name(),
                    "{\"status\":\"ok\",\"path\":\"" + escape(path) + "\"}", false);
        } catch (Exception e) {
            return new ToolResult(toolCallId, name(),
                    "{\"error\":\"" + escape(e.getMessage()) + "\"}", true);
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
