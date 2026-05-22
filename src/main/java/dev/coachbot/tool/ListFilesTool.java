package dev.coachbot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.coachbot.llm.Tool;
import dev.coachbot.llm.ToolResult;
import dev.coachbot.storage.StorageBackend;

import java.util.List;

/**
 * Tool that lists files in the agent's storage backend under a given prefix.
 * The LLM can call this to discover what notes or articles exist.
 */
public class ListFilesTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StorageBackend storage;

    public ListFilesTool(StorageBackend storage) {
        this.storage = storage;
    }

    @Override
    public String name() { return "list_files"; }

    @Override
    public String description() {
        return "List files in storage under a given path prefix. " +
               "Use this to discover what notes exist before reading or referencing them.";
    }

    @Override
    public String jsonSchema() {
        return """
                {"type":"object","properties":\
                {"prefix":{"type":"string","description":"Path prefix to list, e.g. notes/ or coach-bot/wiki/"}},\
                "required":["prefix"]}""";
    }

    @Override
    public ToolResult execute(String toolCallId, String argsJson) {
        try {
            JsonNode args = MAPPER.readTree(argsJson);
            String prefix = args.get("prefix").asText();
            List<String> files = storage.list(prefix);
            String json = MAPPER.writeValueAsString(files);
            return new ToolResult(toolCallId, name(), json, false);
        } catch (Exception e) {
            return new ToolResult(toolCallId, name(),
                    "{\"error\":\"" + escape(e.getMessage()) + "\"}", true);
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
