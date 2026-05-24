package dev.coachbot.scheduler;

import dev.coachbot.core.AgentConfig;
import dev.coachbot.core.AgentRepository;
import dev.coachbot.core.CommandRepository;
import dev.coachbot.core.UserIdentityStore;
import dev.coachbot.llm.LlmBackend;
import dev.coachbot.llm.LlmBackendRegistry;
import dev.coachbot.llm.LlmRequest;
import dev.coachbot.llm.LlmResponse;
import dev.coachbot.storage.StorageBackend;
import dev.coachbot.storage.StorageBackendRegistry;
import dev.coachbot.storage.StorageException;
import dev.coachbot.transport.InboundMessage;
import dev.coachbot.transport.InboundMessageHandler;
import dev.coachbot.transport.TransportPlugin;
import dev.coachbot.transport.TransportRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** A well-formed memory document returned by the bootstrap LLM. */
    private static final String BOOTSTRAPPED_MEMORY = """
            ## Learning Plan
            - [ ] Reactive Streams
            - [ ] Virtual Threads

            ## Topic Statistics
            | Topic | Sessions | Last seen | Level |
            |-------|----------|-----------|-------|
            | Reactive Streams | 0 | — | ⬇️ Needs work |
            | Virtual Threads | 0 | — | ⬇️ Needs work |

            ## Preferences
            (no preferences recorded yet)

            ## Coach Notes
            (no notes yet)
            """;

    private static final ScheduleRepository.AgentSchedule SR_SCHEDULE =
            new ScheduleRepository.AgentSchedule(
                    1L, AGENT_ID, "0 9 * * *", "", true, null, "spaced_review");

    // ── Stubs ──────────────────────────────────────────────────────────────────

    /** Stub LLM that always returns the same response. */
    private static LlmBackend stubLlm(String response) {
        return new LlmBackend() {
            @Override public String id() { return "stub"; }
            @Override public LlmResponse complete(LlmRequest req) { return LlmResponse.text(response); }
        };
    }

    /**
     * Stub LLM that returns different responses on successive calls.
     * Captures every request for later assertion.
     */
    static class SequentialLlm implements LlmBackend {
        final List<LlmRequest> requests = new ArrayList<>();
        private final String[] responses;
        private final AtomicInteger callCount = new AtomicInteger();

        SequentialLlm(String... responses) { this.responses = responses; }

        @Override public String id() { return "stub"; }

        @Override
        public LlmResponse complete(LlmRequest req) {
            requests.add(req);
            int i = callCount.getAndIncrement();
            return LlmResponse.text(i < responses.length ? responses[i] : "");
        }
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

    /** Immutable storage stub — returns a fixed optional on every read. */
    static class MemoryStorage implements StorageBackend {
        private final Optional<String> content;

        MemoryStorage(String content) { this.content = Optional.of(content); }
        MemoryStorage()              { this.content = Optional.empty(); }

        @Override public String id()                               { return "mem"; }
        @Override public void write(String p, String c)           {}
        @Override public void append(String p, String c)          {}
        @Override public Optional<String> read(String p)          { return content; }
        @Override public List<String> list(String p)              { return List.of(); }
    }

    /**
     * Writable storage stub — {@code write()} updates the in-memory content so that
     * subsequent {@code read()} calls see the written value. Models real storage behaviour.
     */
    static class WritableMemoryStorage implements StorageBackend {
        private Optional<String> content;
        String lastWrittenPath;
        String lastWrittenContent;

        WritableMemoryStorage()              { this.content = Optional.empty(); }
        WritableMemoryStorage(String initial){ this.content = Optional.of(initial); }

        @Override public String id()                               { return "mem"; }
        @Override public void append(String p, String c)          {}
        @Override public List<String> list(String p)              { return List.of(); }

        @Override
        public void write(String p, String c) {
            lastWrittenPath    = p;
            lastWrittenContent = c;
            content            = Optional.of(c);
        }

        @Override
        public Optional<String> read(String p) { return content; }
    }

    /** Storage stub that always throws StorageException on read. */
    static class FailingStorage implements StorageBackend {
        @Override public String id()                    { return "mem"; }
        @Override public void write(String p, String c) {}
        @Override public void append(String p, String c){}
        @Override public List<String> list(String p)    { return List.of(); }

        @Override
        public Optional<String> read(String p) throws StorageException {
            throw new StorageException("simulated I/O failure");
        }
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

    /** CommandRepository stub — returns a configurable list of commands (no DB). */
    static class StubCommandRepo extends CommandRepository {
        private final List<AgentCommand> commands;

        StubCommandRepo(AgentCommand... commands) {
            super(null);   // null jdbc — must not call any DB methods
            this.commands = List.of(commands);
        }

        @Override
        public List<AgentCommand> findEnabledByAgent(String agentId) {
            return commands;
        }
    }

    // ── Fixtures ───────────────────────────────────────────────────────────────

    CaptureTransport transport;
    StubCommandRepo  commandRepo;

    /** Build a scheduler wired with the given LLM, storage, and command repo. */
    private AgentScheduler buildScheduler(LlmBackend llm, StorageBackend storage,
                                          CommandRepository cmdRepo) {
        LlmBackendRegistry     llmReg     = new LlmBackendRegistry(List.of(llm));
        TransportRegistry      txReg      = new TransportRegistry(List.of(transport));
        StorageBackendRegistry storageReg = new StorageBackendRegistry(List.of(storage));

        return new AgentScheduler(
                new ScheduleRepository(null) {
                    @Override public List<AgentSchedule> findAllEnabled() { return List.of(); }
                },
                new StubAgentRepo(),
                cmdRepo,
                new StubIdentityStore(),
                llmReg,
                txReg,
                storageReg,
                "UTC"
        );
    }

    /** Convenience overload — uses a no-command stub repo. */
    private AgentScheduler buildScheduler(LlmBackend llm, StorageBackend storage) {
        return buildScheduler(llm, storage, new StubCommandRepo());
    }

    @BeforeEach
    void setUp() {
        transport   = new CaptureTransport();
        commandRepo = new StubCommandRepo();
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
    void trimsWhitespaceFromNoReviewSentinel() {
        // LLM may return "NO_REVIEW\n" — still treated as no-op
        scheduler = buildScheduler(stubLlm("  NO_REVIEW  \n"), new MemoryStorage(MEMORY_WITH_DUE_TOPIC));

        scheduler.fireSpacedReview(SR_SCHEDULE);

        assertThat(transport.sent).isEmpty();
    }

    @Test
    void skipsUserWhenStorageReadFails() {
        // StorageException on read → user is silently skipped
        scheduler = buildScheduler(stubLlm("Let's review!"), new FailingStorage());

        scheduler.fireSpacedReview(SR_SCHEDULE);

        assertThat(transport.sent).isEmpty();
    }

    @Test
    void bootstrapsMemoryWhenEmptyAndSendsFirstReview() {
        // LLM call 1 (bootstrap) → returns initial memory doc
        // LLM call 2 (SR)        → returns a review message
        String reviewMsg = "Let's revisit Reactive Streams — never reviewed before! What is backpressure?";
        var llm     = new SequentialLlm(BOOTSTRAPPED_MEMORY, reviewMsg);
        var storage = new WritableMemoryStorage();   // starts empty

        scheduler = buildScheduler(llm, storage);
        scheduler.fireSpacedReview(SR_SCHEDULE);

        // Bootstrap should have written to storage
        assertThat(storage.lastWrittenContent).isEqualTo(BOOTSTRAPPED_MEMORY);
        assertThat(storage.lastWrittenPath).contains("java-coach");

        // SR review message should have been sent
        assertThat(transport.sent).hasSize(1);
        assertThat(transport.sent.get(0)).isEqualTo(reviewMsg);
    }

    @Test
    void bootstrapIncludesCommandsInPrompt() {
        // When commands exist, their descriptions must appear in the bootstrap user message
        var quiz = new CommandRepository.AgentCommand(1L, AGENT_ID, "/quiz", "Start a Java quiz", true);
        var hint = new CommandRepository.AgentCommand(2L, AGENT_ID, "/hint", "Get a hint for the current topic", true);
        var cmdRepo = new StubCommandRepo(quiz, hint);

        String reviewMsg = "Time to review!";
        var llm     = new SequentialLlm(BOOTSTRAPPED_MEMORY, reviewMsg);
        var storage = new WritableMemoryStorage();

        scheduler = buildScheduler(llm, storage, cmdRepo);
        scheduler.fireSpacedReview(SR_SCHEDULE);

        // First LLM request is the bootstrap call — its user message must mention commands
        assertThat(llm.requests).hasSizeGreaterThanOrEqualTo(1);
        String bootstrapInput = llm.requests.get(0).userMessage();
        assertThat(bootstrapInput).contains("/quiz");
        assertThat(bootstrapInput).contains("Start a Java quiz");
        assertThat(bootstrapInput).contains("/hint");
        assertThat(bootstrapInput).contains("Get a hint for the current topic");
    }

    @Test
    void bootstrapFallsBackToSystemPromptWhenNoCommands() {
        // When no commands exist, the system prompt alone is used for bootstrap
        var llm     = new SequentialLlm(BOOTSTRAPPED_MEMORY, "Let's review!");
        var storage = new WritableMemoryStorage();

        scheduler = buildScheduler(llm, storage, new StubCommandRepo());   // no commands
        scheduler.fireSpacedReview(SR_SCHEDULE);

        String bootstrapInput = llm.requests.get(0).userMessage();
        assertThat(bootstrapInput).contains("You are a Java coach.");   // system prompt text
        assertThat(bootstrapInput).doesNotContain("Available commands:");
    }

    @Test
    void bootstrapSilentWhenLlmReturnsEmpty() {
        // If bootstrap LLM returns blank, user is skipped silently (no crash, no message)
        var llm     = new SequentialLlm("", "This should never be sent");
        var storage = new WritableMemoryStorage();

        scheduler = buildScheduler(llm, storage);
        scheduler.fireSpacedReview(SR_SCHEDULE);

        assertThat(transport.sent).isEmpty();
    }

    // ── Field needed by tests using buildScheduler ─────────────────────────────
    AgentScheduler scheduler;
}
