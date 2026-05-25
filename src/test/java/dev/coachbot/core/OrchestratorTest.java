package dev.coachbot.core;

import dev.coachbot.llm.ConversationMessage;
import dev.coachbot.llm.LlmBackend;
import dev.coachbot.llm.LlmRequest;
import dev.coachbot.llm.LlmResponse;
import dev.coachbot.storage.StorageBackend;
import dev.coachbot.transport.InboundMessage;
import dev.coachbot.transport.InboundMessageHandler;
import dev.coachbot.transport.TransportPlugin;
import dev.coachbot.transport.TransportRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration-style test that wires a full Orchestrator + GroupSession pipeline
 * with in-memory fakes — no Spring context, no real LLM, no real transport.
 */
class OrchestratorIntegrationTest {

    // ── Fakes ──────────────────────────────────────────────────────────────────

    static class EchoLlm implements LlmBackend {
        @Override public String id() { return "echo"; }
        @Override public LlmResponse complete(LlmRequest req) {
            return LlmResponse.text("Echo: " + req.userMessage());
        }
    }

    static class CaptureLlm implements LlmBackend {
        volatile LlmRequest lastRequest;
        @Override public String id() { return "capture"; }
        @Override public LlmResponse complete(LlmRequest req) {
            // Ignore background bootstrap calls (userId="bootstrap") — they run on a virtual
            // thread and would otherwise race with test assertions on lastRequest.
            if (!"bootstrap".equals(req.userId())) {
                this.lastRequest = req;
            }
            return LlmResponse.text("OK");
        }
    }

    static class NoopStorage implements StorageBackend {
        @Override public String id() { return "noop"; }
        @Override public void write(String p, String c) {}
        @Override public void append(String p, String c) {}
        @Override public Optional<String> read(String p) { return Optional.empty(); }
        @Override public List<String> list(String p) { return List.of(); }
    }

    /** No-op CommandRepository stub — returns empty lists, skips DB. */
    static class NoopCommandRepository extends CommandRepository {
        NoopCommandRepository() { super(null); }

        @Override public List<dev.coachbot.core.CommandRepository.AgentCommand> findByAgent(String a) { return List.of(); }
        @Override public List<dev.coachbot.core.CommandRepository.AgentCommand> findEnabledByAgent(String a) { return List.of(); }
        @Override public long insert(String a, String t, String d) { return 0; }
        @Override public void setEnabled(long id, boolean e) {}
        @Override public void delete(long id) {}
    }

    /** No-op AgentRepository stub — skips all DB operations (no JdbcTemplate needed). */
    static class NoopAgentRepository extends AgentRepository {
        NoopAgentRepository() { super(null, null); }

        @Override public void seedIfEmpty() {}
        @Override public void updateSystemPrompt(String agentId, String newPrompt) {}
        @Override public void updateLlmBackend(String agentId, String newBackend) {}
        @Override public void setEnabled(String agentId, boolean enabled) {}
        @Override public void insertTransport(String agentId, String transportId, String chatId) {}
        @Override public void deleteTransport(String agentId, String transportId, String chatId) {}
        @Override public java.util.Optional<AgentConfig> findById(String id) { return java.util.Optional.empty(); }
        @Override public List<AgentConfig> findAll() { return List.of(); }
        @Override public List<AgentConfig> findAllEnabled() { return List.of(); }
        @Override public List<AgentConfig> findByTransport(String t, String c) { return List.of(); }
        @Override public List<AgentRepository.TransportBinding> findTransports(String agentId) { return List.of(); }
    }

    /** In-memory HistoryStore — no DB required in tests. */
    static class InMemoryHistoryStore implements HistoryStore {
        private final Map<String, List<ConversationMessage>> store = new ConcurrentHashMap<>();

        private String key(String agentId, String userId) { return agentId + ":" + userId; }

        @Override
        public List<ConversationMessage> load(String agentId, String userId, int maxMessages) {
            List<ConversationMessage> all = store.getOrDefault(key(agentId, userId), List.of());
            int from = Math.max(0, all.size() - maxMessages);
            return new ArrayList<>(all.subList(from, all.size()));
        }

        @Override
        public void append(String agentId, String userId, ConversationMessage message) {
            store.computeIfAbsent(key(agentId, userId), k -> new ArrayList<>()).add(message);
        }

        @Override
        public void trim(String agentId, String userId, int keepCount) {
            List<ConversationMessage> list = store.get(key(agentId, userId));
            if (list != null && list.size() > keepCount) {
                list.subList(0, list.size() - keepCount).clear();
            }
        }
    }

    static class CaptureTransport implements TransportPlugin {
        final BlockingQueue<String> sent = new ArrayBlockingQueue<>(100);
        InboundMessageHandler handler;
        @Override public String id() { return "test"; }
        @Override public void start(InboundMessageHandler h) { this.handler = h; }
        @Override public void send(String chatId, String text) { sent.offer(text); }
        @Override public boolean isConnected() { return true; }
        @Override public void stop() {}
    }

    // ── Setup ──────────────────────────────────────────────────────────────────

    CaptureTransport transport;
    CaptureLlm llm;
    GroupSession session;
    InMemoryHistoryStore historyStore;

    /** Identity store stub: canonical ID = sender ID (no cross-transport mapping needed in tests). */
    static final UserIdentityStore PASSTHROUGH_IDENTITY = (transportId, senderId) -> senderId;

    @BeforeEach
    void setUp() {
        transport    = new CaptureTransport();
        llm          = new CaptureLlm();
        historyStore = new InMemoryHistoryStore();

        AgentConfig agent = new AgentConfig(
                "test-agent", "Test Coach", "You are a helpful coach.",
                "capture", "noop", "@Bot", false, true);

        TransportRegistry transportRegistry = new TransportRegistry(List.of(transport));
        // PASSTHROUGH_TRANSLATION: translation disabled (language = "en")
        dev.coachbot.translation.TranslationService noopTranslation =
                new dev.coachbot.translation.TranslationService(
                        (t, f, to) -> java.util.Optional.empty(), "en");  // lang=en → translation disabled, client never called
        session = new GroupSession(agent, llm, new NoopStorage(), transportRegistry,
                historyStore, PASSTHROUGH_IDENTITY, noopTranslation,
                new NoopAgentRepository(), new NoopCommandRepository());
        session.start();
        transport.start(msg -> session.enqueue(msg));
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    void message_reaches_llm_and_response_is_sent() throws InterruptedException {
        transport.handler.onMessage(new InboundMessage(
                "test", "chat1", "user1", "Alice",
                "Hello coach", Instant.now()
        ));

        String response = transport.sent.poll(3, TimeUnit.SECONDS);
        assertThat(response).isEqualTo("OK");
        assertThat(llm.lastRequest.userMessage()).isEqualTo("Hello coach");
        assertThat(llm.lastRequest.systemPrompt()).isEqualTo("You are a helpful coach.");
    }

    @Test
    void history_accumulates_across_turns() throws InterruptedException {
        // First turn
        transport.handler.onMessage(new InboundMessage(
                "test", "chat1", "user1", "Alice", "first message", Instant.now()));
        transport.sent.poll(3, TimeUnit.SECONDS);

        // Second turn — history should contain the first exchange
        transport.handler.onMessage(new InboundMessage(
                "test", "chat1", "user1", "Alice", "second message", Instant.now()));
        transport.sent.poll(3, TimeUnit.SECONDS);

        assertThat(llm.lastRequest.history()).hasSize(2);
        assertThat(llm.lastRequest.history().get(0).content()).isEqualTo("first message");
        assertThat(llm.lastRequest.history().get(1).content()).isEqualTo("OK");
    }

    @Test
    void history_survives_session_restart() throws InterruptedException {
        // Send a message in the first session
        transport.handler.onMessage(new InboundMessage(
                "test", "chat1", "user1", "Alice", "before restart", Instant.now()));
        transport.sent.poll(3, TimeUnit.SECONDS);

        // NEW session backed by the SAME historyStore (simulates JVM restart)
        AgentConfig agent = new AgentConfig(
                "test-agent", "Test Coach", "You are a helpful coach.",
                "capture", "noop", "@Bot", false, true);
        GroupSession session2 = new GroupSession(
                agent, llm, new NoopStorage(),
                new TransportRegistry(List.of(transport)), historyStore, PASSTHROUGH_IDENTITY,
                new dev.coachbot.translation.TranslationService(
                        new dev.coachbot.translation.SimplyTranslateClient("https://api.simplytranslate.ai", ""), "en"),
                new NoopAgentRepository(), new NoopCommandRepository());
        session2.start();
        transport.start(msg -> session2.enqueue(msg));

        transport.handler.onMessage(new InboundMessage(
                "test", "chat1", "user1", "Alice", "after restart", Instant.now()));
        transport.sent.poll(3, TimeUnit.SECONDS);

        // History loaded from store must contain the exchange from before the restart
        assertThat(llm.lastRequest.history()).isNotEmpty();
        assertThat(llm.lastRequest.history().get(0).content()).isEqualTo("before restart");

        session2.stop();
    }

    @Test
    void two_parallel_sessions_histories_are_isolated() throws InterruptedException {
        // Second agent sharing the same historyStore (simulates java-coach + english-coach in same JVM)
        var transport2   = new CaptureTransport();
        var llm2         = new CaptureLlm();
        var englishAgent = new AgentConfig(
                "english-coach", "English Coach", "You are an English coach.",
                "capture", "noop", "@English", false, true);
        var noopTranslation2 = new dev.coachbot.translation.TranslationService(
                new dev.coachbot.translation.SimplyTranslateClient("https://api.simplytranslate.ai", ""), "en");
        var englishSession = new GroupSession(
                englishAgent, llm2, new NoopStorage(),
                new TransportRegistry(List.of(transport2)),
                historyStore,            // same store — must not share history
                PASSTHROUGH_IDENTITY, noopTranslation2,
                new NoopAgentRepository(), new NoopCommandRepository());
        englishSession.start();
        transport2.start(msg -> englishSession.enqueue(msg));

        // Two turns in the java session
        transport.handler.onMessage(new InboundMessage("test", "chat1", "user1", "Alice", "java msg 1", Instant.now()));
        transport.sent.poll(3, TimeUnit.SECONDS);
        transport.handler.onMessage(new InboundMessage("test", "chat1", "user1", "Alice", "java msg 2", Instant.now()));
        transport.sent.poll(3, TimeUnit.SECONDS);

        // First turn in the english session — history must be empty (not contaminated by java session)
        transport2.handler.onMessage(new InboundMessage("test", "chat1", "user1", "Alice", "english msg", Instant.now()));
        transport2.sent.poll(3, TimeUnit.SECONDS);

        assertThat(llm2.lastRequest.history()).isEmpty();
        assertThat(llm2.lastRequest.systemPrompt()).isEqualTo("You are an English coach.");

        // Second turn in english — only sees its own prior exchange
        transport2.handler.onMessage(new InboundMessage("test", "chat1", "user1", "Alice", "english msg 2", Instant.now()));
        transport2.sent.poll(3, TimeUnit.SECONDS);

        assertThat(llm2.lastRequest.history()).hasSize(2);
        assertThat(llm2.lastRequest.history().get(0).content()).isEqualTo("english msg");

        englishSession.stop();
    }

    @Test
    void different_users_have_isolated_histories() throws InterruptedException {
        transport.handler.onMessage(new InboundMessage(
                "test", "chat1", "user1", "Alice", "alice msg", Instant.now()));
        transport.sent.poll(3, TimeUnit.SECONDS);

        transport.handler.onMessage(new InboundMessage(
                "test", "chat1", "user2", "Bob", "bob msg", Instant.now()));
        transport.sent.poll(3, TimeUnit.SECONDS);

        // Bob's request should have no history from Alice
        assertThat(llm.lastRequest.userId()).isEqualTo("user2");
        assertThat(llm.lastRequest.history()).isEmpty();
    }

    // ── /challenge tests ───────────────────────────────────────────────────────

    @Test
    void challenge_generates_exercise_from_agent_context() throws InterruptedException {
        transport.handler.onMessage(new InboundMessage(
                "test", "chat1", "user1", "Alice", "/challenge", Instant.now()));

        String reply = transport.sent.poll(3, TimeUnit.SECONDS);
        assertThat(reply).isNotNull().isNotBlank();

        // LLM must have received the agent system prompt in the user message
        assertThat(llm.lastRequest.userMessage())
                .contains("Agent context:")
                .contains("You are a helpful coach.");
    }

    /** Storage backend that captures writes — used to verify async memory updates. */
    static class WritableStorage implements StorageBackend {
        final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

        @Override public String id() { return "writable"; }
        @Override public void write(String p, String c) { store.put(p, c); }
        @Override public void append(String p, String c) { store.merge(p, c, (a, b) -> a + b); }
        @Override public Optional<String> read(String p) { return Optional.ofNullable(store.get(p)); }
        @Override public List<String> list(String p) { return List.of(); }
        @Override public void delete(String p) { store.remove(p); }
    }

    /**
     * LLM fake for challenge flow tests:
     * - Calls containing "Exercise:" → evaluation response with MEMORY: note
     * - Calls containing "Current memory:" → dummy memory document (from updateAsync)
     * - Bootstrap calls (userId = "bootstrap") → minimal memory stub
     * - Everything else → challenge text
     */
    static class ChallengeLlm implements LlmBackend {
        volatile LlmRequest lastReq;

        @Override public String id() { return "challenge-llm"; }

        @Override
        public LlmResponse complete(LlmRequest req) {
            if ("bootstrap".equals(req.userId())) {
                return LlmResponse.text("## Learning Plan\n- [ ] Topic A\n");
            }
            lastReq = req;
            if (req.userMessage().contains("Exercise:")) {
                return LlmResponse.text(
                        "✅ Correct\n\nGood explanation.\n\nMEMORY: User demonstrated topic A.");
            }
            if (req.userMessage().contains("Current memory:")) {
                return LlmResponse.text("## Learning Plan\n- [x] Topic A\n");
            }
            // Challenge generation: return a recognisable challenge string
            return LlmResponse.text("Explain the concept.\n\nSend your answer when ready.");
        }
    }

    @Test
    void challenge_answer_triggers_evaluation_and_memory_update() throws InterruptedException {
        var challengeLlm = new ChallengeLlm();
        var storage      = new WritableStorage();

        AgentConfig agent2 = new AgentConfig(
                "test-agent", "Test Coach", "You are a helpful coach.",
                "challenge-llm", "writable", "@Bot", false, true);
        var transport2 = new CaptureTransport();
        var session2 = new GroupSession(agent2, challengeLlm, storage,
                new TransportRegistry(List.of(transport2)),
                new InMemoryHistoryStore(), PASSTHROUGH_IDENTITY,
                new dev.coachbot.translation.TranslationService(
                        (t, f, to) -> Optional.empty(), "en"),
                new NoopAgentRepository(), new NoopCommandRepository());
        session2.start();
        transport2.start(msg -> session2.enqueue(msg));

        // Step 1: request challenge
        transport2.handler.onMessage(new InboundMessage(
                "test", "chat1", "user1", "Alice", "/challenge", Instant.now()));
        transport2.sent.poll(3, TimeUnit.SECONDS);  // challenge text

        // Step 2: submit answer
        transport2.handler.onMessage(new InboundMessage(
                "test", "chat1", "user1", "Alice", "Here is my answer.", Instant.now()));
        String feedback = transport2.sent.poll(3, TimeUnit.SECONDS);

        // Feedback is shown to user without MEMORY: line
        assertThat(feedback).contains("✅ Correct").doesNotContain("MEMORY:");

        // Evaluate LLM request must contain the original exercise and the answer
        LlmRequest evalReq = challengeLlm.lastReq;
        assertThat(evalReq.userMessage())
                .contains("Exercise:")
                .contains("Answer:")
                .contains("Here is my answer.");

        // Async memory update must write to storage
        Thread.sleep(500);
        assertThat(storage.store).containsKey("coach-bot/memory/test-agent/user1.md");

        session2.stop();
    }

    @Test
    void challenge_cancel_reverts_to_normal_llm() throws InterruptedException {
        // Start a challenge
        transport.handler.onMessage(new InboundMessage(
                "test", "chat1", "user1", "Alice", "/challenge", Instant.now()));
        transport.sent.poll(3, TimeUnit.SECONDS);

        // Cancel it
        transport.handler.onMessage(new InboundMessage(
                "test", "chat1", "user1", "Alice", "/cancel", Instant.now()));
        String cancelReply = transport.sent.poll(3, TimeUnit.SECONDS);
        assertThat(cancelReply).contains("Cancelled");

        // Next normal message must go to the LLM as regular conversation (not evaluated as a solution)
        transport.handler.onMessage(new InboundMessage(
                "test", "chat1", "user1", "Alice", "just chatting", Instant.now()));
        transport.sent.poll(3, TimeUnit.SECONDS);

        assertThat(llm.lastRequest.userMessage()).isEqualTo("just chatting");
    }
}
