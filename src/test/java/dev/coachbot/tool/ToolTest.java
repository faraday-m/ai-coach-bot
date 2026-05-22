package dev.coachbot.tool;

import dev.coachbot.llm.ToolResult;
import dev.coachbot.storage.StorageBackend;
import dev.coachbot.storage.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class ToolTest {

    // ── Fake storage ──────────────────────────────────────────────────────────

    static class MapStorage implements StorageBackend {
        final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

        @Override public String id() { return "map"; }
        @Override public void write(String path, String content) { store.put(path, content); }
        @Override public void append(String path, String content) {
            store.merge(path, content, (a, b) -> a + b);
        }
        @Override public Optional<String> read(String path) { return Optional.ofNullable(store.get(path)); }
        @Override public List<String> list(String prefix) {
            return store.keySet().stream().filter(k -> k.startsWith(prefix)).sorted().toList();
        }
        @Override public void delete(String path) { store.remove(path); }
    }

    private MapStorage storage;

    @BeforeEach
    void setUp() { storage = new MapStorage(); }

    // ── WriteFileTool ─────────────────────────────────────────────────────────

    @Test
    void writeFile_savesContentToStorage() {
        var tool = new WriteFileTool(storage);
        ToolResult result = tool.execute("call-1",
                "{\"path\":\"notes/test.md\",\"content\":\"# Hello\"}");

        assertThat(result.isError()).isFalse();
        assertThat(storage.store).containsKey("notes/test.md");
        assertThat(storage.store.get("notes/test.md")).isEqualTo("# Hello");
    }

    @Test
    void writeFile_returnsError_onMalformedJson() {
        var tool = new WriteFileTool(storage);
        ToolResult result = tool.execute("call-1", "not-json");

        assertThat(result.isError()).isTrue();
        assertThat(result.resultJson()).contains("error");
        assertThat(storage.store).isEmpty();
    }

    @Test
    void writeFile_preservesToolCallId() {
        var tool = new WriteFileTool(storage);
        ToolResult result = tool.execute("my-id-42",
                "{\"path\":\"x.md\",\"content\":\"y\"}");

        assertThat(result.toolCallId()).isEqualTo("my-id-42");
        assertThat(result.toolName()).isEqualTo("write_file");
    }

    // ── ReadFileTool ──────────────────────────────────────────────────────────

    @Test
    void readFile_returnsContent_whenExists() {
        storage.store.put("notes/java.md", "# Java GC");
        var tool = new ReadFileTool(storage);
        ToolResult result = tool.execute("call-2", "{\"path\":\"notes/java.md\"}");

        assertThat(result.isError()).isFalse();
        assertThat(result.resultJson()).contains("Java GC");
    }

    @Test
    void readFile_returnsError_whenNotFound() {
        var tool = new ReadFileTool(storage);
        ToolResult result = tool.execute("call-3", "{\"path\":\"missing.md\"}");

        assertThat(result.isError()).isTrue();
        assertThat(result.resultJson()).contains("not found");
    }

    // ── ListFilesTool ─────────────────────────────────────────────────────────

    @Test
    void listFiles_returnsMatchingPaths() {
        storage.store.put("notes/gc.md", "");
        storage.store.put("notes/threads.md", "");
        storage.store.put("coach-bot/memory/agent1/user.md", "");
        var tool = new ListFilesTool(storage);
        ToolResult result = tool.execute("call-4", "{\"prefix\":\"notes/\"}");

        assertThat(result.isError()).isFalse();
        assertThat(result.resultJson()).contains("notes/gc.md");
        assertThat(result.resultJson()).contains("notes/threads.md");
        assertThat(result.resultJson()).doesNotContain("coach-bot");
    }

    @Test
    void listFiles_returnsEmptyArray_whenNoneMatch() {
        var tool = new ListFilesTool(storage);
        ToolResult result = tool.execute("call-5", "{\"prefix\":\"nonexistent/\"}");

        assertThat(result.isError()).isFalse();
        assertThat(result.resultJson()).isEqualTo("[]");
    }
}
