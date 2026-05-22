package dev.coachbot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.coachbot.llm.Tool;
import dev.coachbot.llm.ToolResult;
import dev.coachbot.storage.StorageBackend;

/**
 * Tool that reads a file from the agent's storage backend.
 * The LLM can call this to look up previous notes or saved articles.
 */
public class ReadFileTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StorageBackend storage;

    public ReadFileTool(StorageBackend storage) {
        this.storage = storage;
    }

    @Override
    public String name() { return "read_file"; }

    @Override
    public String description() {
        return "Read the contents of a file from storage. " +
               "Use this to retrieve previously saved notes or articles.";
    }

    @Override
    public String jsonSchema() {
        return """
                {"type":"object","properties":\
                {"path":{"type":"string","description":"File path relative to storage root"}},\
                "required":["path"]}""";
    }

    @Override
    public ToolResult execute(String toolCallId, String argsJson) {
        try {
            JsonNode args = MAPPER.readTree(argsJson);
            String path = args.get("path").asText();
            String content = storage.read(path).orElse(null);
            if (content == null) {
                return new ToolResult(toolCallId, name(),
                        "{\"error\":\"file not found: " + escape(path) + "\"}", true);
            }
            // Return content as a JSON string value
            String escaped = MAPPER.writeValueAsString(content); // properly escaped JSON string
            return new ToolResult(toolCallId, name(),
                    "{\"path\":\"" + escape(path) + "\",\"content\":" + escaped + "}", false);
        } catch (Exception e) {
            return new ToolResult(toolCallId, name(),
                    "{\"error\":\"" + escape(e.getMessage()) + "\"}", true);
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
