package dev.coachbot.scheduler;

import dev.coachbot.core.AgentConfig;
import dev.coachbot.core.AgentRepository;
import dev.coachbot.core.UserIdentityStore;
import dev.coachbot.llm.LlmBackend;
import dev.coachbot.llm.LlmBackendRegistry;
import dev.coachbot.llm.LlmRequest;
import dev.coachbot.llm.LlmResponse;
import dev.coachbot.storage.StorageBackend;
import dev.coachbot.storage.StorageBackendRegistry;
import dev.coachbot.transport.InboundMessage;
import dev.coachbot.transport.InboundMessageHandler;
import dev.coachbot.transport.TransportPlugin;
import dev.coachbot.transport.TransportRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@code spaced_review} firing path in {@link AgentScheduler}.
 *
 * <p>All dependencies are hand-rolled stubs — no Spring context, no Mockito, no DB.
 * The scheduler's {@link AgentScheduler#fireSpacedReview} method is package-private
 * so tests can call it directly without waiting for a real cron job.
 */
class AgentSchedulerSpacedReviewTest {

    // ── Constants ──────────────────────────────────────────────────────────────

    private static final String AGENT_ID    = "java-coach";
    private static final String CANON_USER  = "user:abc123";
    private static final String TRANSPORT_KEY = "telegram:111222333";
    private static final String TRANSPORT_ID  = "telegram";
    private static final String CHAT_ID       = "111222333";

    /** A memory document with a ➡️ Familiar topic last seen 8 days ago — always due. */
    private static final String MEMORY_WITH_DUE_TOPIC = """
            ## Topic Statistics
            | Topic | Sessions | Last seen | Level |
            |-------|----------|-----------|-------|
            | Reactive Streams | 2 | 2026-01-01 | ➡️ Familiar |
            """;

    private static final ScheduleRepository.AgentSchedule SR_SCHEDULE =
            new ScheduleRepository.AgentSchedule(
                    1L, AGENT_ID, "0 9 * * *", "", true, null, "spaced_review");

    // ── Stubs ──────────────────────────────────────────────────────────────────

    /** Stub LLM that returns a fixed response. */
    private static LlmBackend stubLlm(String response) {
        return new LlmBackend() {
            @Override public String id() { return "stub"; }
            @Override public LlmResponse complete(LlmRequest req) { return LlmResponse.text(response); }
        };
    }

    /** Transport that records every send() call. */
    static class CaptureTransport implements TransportPlugin {
        final List<String> sent = new ArrayList<>();
        String lastChatId;

        @Override public String id() { return TRANSPORT_ID; }
        @Override public void start(InboundMessageHandler h) {}
        @Override public void send(String chatId, String text) { lastChatId = chatId; sent.add(text); }
        @Override public boolean isConnected() { return true; }
        @Override public void stop() {}
    }

    /** Storage that can optionally hold a memory file. */
    static class MemoryStorage implements StorageBackend {
        private final Optional<String> content;

        MemoryStorage(String content) { this.content = Optional.of(content); }
        MemoryStorage()              { this.content = Optional.empty(); }

        @Override public String id()                                                           { return "mem"; }
        @Override public void write(String p, String c)                                    {}
        @Override public void append(String p, String c)                                   {}
        @Override public Optional<String> read(String p)                                   { return content; }
        @Override public List<String> list(String p)                                       { return List.of(); }
    }

    /** AgentRepository stub — returns one fixed agent and one canonical user. */
    static class StubAgentRepo extends AgentRepository {
        StubAgentRepo() { super(null, null); }

        @Override public void seedIfEmpty() {}

        @Override
        public Optional<AgentConfig> findById(String id) {
            return Optional.of(new AgentConfig(
                    AGENT_ID, "Java Coach", "You are a Java coach.",
                    "stub", "mem", "@coach", false, true));
        }

        @Override
        public List<String> findUsersByAgent(String agentId) {
            return List.of(CANON_USER);
        }

        @Override
        public List<AgentRepository.TransportBinding> findTransports(String agentId) {
            return List.of(new AgentRepository.TransportBinding(TRANSPORT_ID, CHAT_ID));
        }
    }

    /** UserIdentityStore stub — returns the test transport key for any canonical ID. */
    static class StubIdentityStore implements UserIdentityStore {
        @Override
        public String resolve(String transportId, String senderId) {
            return CANON_USER;
        }

        @Override
        public List<String> findTransportKeysByCanonicalId(String canonicalId) {
            return List.of(TRANSPORT_KEY);
        }
    }

    // ── Fixtures ───────────────────────────────────────────────────────────────

    CaptureTransport transport;
    AgentScheduler   scheduler;

    /** Build a scheduler wired with the given LLM and storage. */
    private AgentScheduler buildScheduler(LlmBackend llm, StorageBackend storage) {
        LlmBackendRegistry     llmReg     = new LlmBackendRegistry(List.of(llm));
        TransportRegistry      txReg      = new TransportRegistry(List.of(transport));
        StorageBackendRegistry storageReg = new StorageBackendRegistry(List.of(storage));

        return new AgentScheduler(
                new ScheduleRepository(null) {
                    @Override public List<AgentSchedule> findAllEnabled() { return List.of(); }
                },
                new StubAgentRepo(),
                new StubIdentityStore(),
                llmReg,
                txReg,
                storageReg,
                "UTC"
        );
    }

    @BeforeEach
    void setUp() {
        transport = new CaptureTransport();
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    void sendsMessageWhenTopicDue() {
        String reviewMsg = "Time to revisit Reactive Streams! It's been 8 days. What is backpressure?";
        scheduler = buildScheduler(stubLlm(reviewMsg), new MemoryStorage(MEMORY_WITH_DUE_TOPIC));

        scheduler.fireSpacedReview(SR_SCHEDULE);

        assertThat(transport.sent).hasSize(1);
        assertThat(transport.sent.get(0)).isEqualTo(reviewMsg);
        assertThat(transport.lastChatId).isEqualTo(CHAT_ID);
    }

    @Test
    void silentWhenNothingDue() {
        // LLM says no review needed today
        scheduler = buildScheduler(stubLlm("NO_REVIEW"), new MemoryStorage(MEMORY_WITH_DUE_TOPIC));

        scheduler.fireSpacedReview(SR_SCHEDULE);

        assertThat(transport.sent).isEmpty();
    }

    @Test
    void skipsUserWithNoMemory() {
        // Storage returns empty — user has no memory file yet
        scheduler = buildScheduler(stubLlm("Let's review!"), new MemoryStorage());

        scheduler.fireSpacedReview(SR_SCHEDULE);

        assertThat(transport.sent).isEmpty();
    }

    @Test
    void trimsWhitespaceFromNoReviewSentinel() {
        // LLM may return "NO_REVIEW\n" — still treated as no-op
        scheduler = buildScheduler(stubLlm("  NO_REVIEW  \n"), new MemoryStorage(MEMORY_WITH_DUE_TOPIC));

        scheduler.fireSpacedReview(SR_SCHEDULE);

        assertThat(transport.sent).isEmpty();
    }
}
