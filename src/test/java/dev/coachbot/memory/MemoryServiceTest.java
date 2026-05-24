package dev.coachbot.memory;

import dev.coachbot.llm.LlmBackend;
import dev.coachbot.llm.LlmRequest;
import dev.coachbot.llm.LlmResponse;
import dev.coachbot.storage.StorageBackend;
import dev.coachbot.storage.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryServiceTest {

    // ── Fakes ──────────────────────────────────────────────────────────────────

    static class MapStorage implements StorageBackend {
        final java.util.concurrent.ConcurrentHashMap<String, String> store = new java.util.concurrent.ConcurrentHashMap<>();

        @Override public String id() { return "map"; }
        @Override public void write(String path, String content) { store.put(path, content); }
        @Override public void append(String path, String content) {
            store.merge(path, content, (a, b) -> a + b);
        }
        @Override public Optional<String> read(String path) { return Optional.ofNullable(store.get(path)); }
        @Override public List<String> list(String prefix) {
            return store.keySet().stream().filter(k -> k.startsWith(prefix)).toList();
        }
        @Override public void delete(String path) { store.remove(path); }
    }

    static class CaptureLlm implements LlmBackend {
        final List<LlmRequest> received = new CopyOnWriteArrayList<>();
        String fixedResponse = "## Learning Plan\n- [x] Java GC";

        @Override public String id() { return "capture"; }
        @Override public LlmResponse complete(LlmRequest req) {
            received.add(req);
            return LlmResponse.text(fixedResponse);
        }
    }

    private MapStorage storage;
    private CaptureLlm llm;
    private MemoryService service;

    private static final String BOOTSTRAP_PROMPT = "You are a bootstrap assistant.";

    @BeforeEach
    void setUp() {
        storage = new MapStorage();
        llm     = new CaptureLlm();
        service = new MemoryService(storage, llm, "You are a memory assistant.", BOOTSTRAP_PROMPT);
    }

    // ── load ───────────────────────────────────────────────────────────────────

    @Test
    void load_returnsEmpty_whenNoFile() {
        assertThat(service.load("agent1", "user:abc")).isEmpty();
    }

    @Test
    void load_returnsContent_whenFileExists() {
        storage.store.put("coach-bot/memory/agent1/user_abc.md", "# Memory");
        assertThat(service.load("agent1", "user:abc")).contains("# Memory");
    }

    // ── safeFilename ───────────────────────────────────────────────────────────

    @Test
    void safeFilename_replacesSpecialChars() {
        assertThat(MemoryService.safeFilename("tg:123456")).isEqualTo("tg_123456");
        assertThat(MemoryService.safeFilename("user:abc-def_01")).isEqualTo("user_abc-def_01");
        assertThat(MemoryService.safeFilename("jabber:me@server.org")).isEqualTo("jabber_me_server_org");
    }

    // ── memoryPath ─────────────────────────────────────────────────────────────

    @Test
    void memoryPath_usesCorrectTemplate() {
        assertThat(service.memoryPath("java-coach", "tg:123"))
                .isEqualTo("coach-bot/memory/java-coach/tg_123.md");
    }

    // ── updateAsync ────────────────────────────────────────────────────────────

    @Test
    void updateAsync_callsLlmAndWritesResult() throws InterruptedException {
        service.updateAsync("agent1", "user:abc", "New note content");

        // Wait for the virtual thread to complete
        Thread.sleep(500);

        // LLM was called once with the memory update prompt
        assertThat(llm.received).hasSize(1);
        LlmRequest req = llm.received.get(0);
        assertThat(req.userMessage()).contains("New note content");
        assertThat(req.agentId()).isEqualTo("agent1");

        // Result was written to storage
        String path = service.memoryPath("agent1", "user:abc");
        assertThat(storage.store).containsKey(path);
        assertThat(storage.store.get(path)).isEqualTo(llm.fixedResponse);
    }

    @Test
    void updateAsync_includesExistingMemoryInPrompt() throws InterruptedException {
        String existingMemory = "## Learning Plan\n- [~] GC";
        storage.store.put(service.memoryPath("agent1", "user:abc"), existingMemory);

        service.updateAsync("agent1", "user:abc", "GC deep dive note");
        Thread.sleep(500);

        assertThat(llm.received.get(0).userMessage())
                .contains(existingMemory)
                .contains("GC deep dive note");
    }

    @Test
    void updateAsync_doesNotThrow_whenLlmReturnsEmpty() throws InterruptedException {
        llm.fixedResponse = "   ";
        service.updateAsync("agent1", "user:abc", "Some content");
        Thread.sleep(300);

        // Nothing written — no exception propagated
        assertThat(storage.store).doesNotContainKey(service.memoryPath("agent1", "user:abc"));
    }

    // ── appendNote ─────────────────────────────────────────────────────────────

    @Test
    void appendNote_createsFileWithHeader_whenNotExists() {
        service.appendNote("agent1", "user:abc", "Focus on Kafka");
        String content = storage.store.get(service.memoryPath("agent1", "user:abc"));
        assertThat(content).contains("## Coach Notes");
        assertThat(content).contains("Focus on Kafka");
    }

    @Test
    void appendNote_appendsToExistingFile() {
        String path = service.memoryPath("agent1", "user:abc");
        storage.store.put(path, "## Learning Plan\n- [ ] Kafka\n");
        service.appendNote("agent1", "user:abc", "Asked about Kafka partitions");
        assertThat(storage.store.get(path))
                .contains("## Learning Plan")
                .contains("Asked about Kafka partitions");
    }

    // ── reset ──────────────────────────────────────────────────────────────────

    @Test
    void reset_deletesFile() {
        String path = service.memoryPath("agent1", "user:abc");
        storage.store.put(path, "some memory");
        service.reset("agent1", "user:abc");
        assertThat(storage.store).doesNotContainKey(path);
    }

    @Test
    void reset_noopsWhenFileAbsent() {
        // Should not throw
        service.reset("agent1", "user:abc");
    }

    // ── bootstrapAsync ─────────────────────────────────────────────────────────

    @Test
    void bootstrapAsync_writesGeneratedMemory() throws InterruptedException {
        llm.fixedResponse = "## Learning Plan\n- [ ] Kafka\n## Topic Statistics\n| Topic | Sessions | Last seen | Level |\n|---|---|---|---|\n| Kafka | 0 | — | ⬇️ Needs work |";

        service.bootstrapAsync("agent1", "user:abc", "You are a Kafka coach.", "/quiz — Start a quiz");
        Thread.sleep(500);

        String path = service.memoryPath("agent1", "user:abc");
        assertThat(storage.store).containsKey(path);
        assertThat(storage.store.get(path)).contains("Kafka");
    }

    @Test
    void bootstrapAsync_includesSystemPromptAndCommandsInRequest() throws InterruptedException {
        service.bootstrapAsync("agent1", "user:abc", "You are a Kafka coach.", "/quiz — Start a quiz\n/hint — Get a hint");
        Thread.sleep(500);

        assertThat(llm.received).hasSize(1);
        String userMsg = llm.received.get(0).userMessage();
        assertThat(userMsg).contains("You are a Kafka coach.");
        assertThat(userMsg).contains("/quiz — Start a quiz");
        assertThat(userMsg).contains("/hint — Get a hint");
    }

    @Test
    void bootstrapAsync_skipsWhenMemoryAlreadyExists() throws InterruptedException {
        // Pre-populate memory — bootstrap should not overwrite it
        String path = service.memoryPath("agent1", "user:abc");
        storage.store.put(path, "## Existing memory");

        service.bootstrapAsync("agent1", "user:abc", "You are a coach.", "");
        Thread.sleep(500);

        // LLM must not have been called
        assertThat(llm.received).isEmpty();
        // Existing memory must be untouched
        assertThat(storage.store.get(path)).isEqualTo("## Existing memory");
    }

    @Test
    void bootstrapAsync_skipsWhenNoBootstrapPromptConfigured() throws InterruptedException {
        MemoryService noBootstrap = new MemoryService(storage, llm, "update prompt");  // no bootstrap template

        noBootstrap.bootstrapAsync("agent1", "user:abc", "You are a coach.", "");
        Thread.sleep(300);

        assertThat(llm.received).isEmpty();
        assertThat(storage.store).doesNotContainKey(noBootstrap.memoryPath("agent1", "user:abc"));
    }

    @Test
    void bootstrapAsync_worksWithoutCommandsText() throws InterruptedException {
        service.bootstrapAsync("agent1", "user:abc", "You are a Java coach.", "");
        Thread.sleep(500);

        String path = service.memoryPath("agent1", "user:abc");
        assertThat(llm.received).hasSize(1);
        String userMsg = llm.received.get(0).userMessage();
        assertThat(userMsg).contains("You are a Java coach.");
        assertThat(userMsg).doesNotContain("Available commands:");
        assertThat(storage.store).containsKey(path);
    }
}
