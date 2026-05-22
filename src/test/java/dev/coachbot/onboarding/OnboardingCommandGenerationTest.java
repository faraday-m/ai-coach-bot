package dev.coachbot.onboarding;

import dev.coachbot.llm.LlmBackend;
import dev.coachbot.llm.LlmRequest;
import dev.coachbot.llm.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for post-onboarding slash-command generation in {@link OnboardingFlow}.
 *
 * <p>Uses a stub {@link LlmBackend} that returns a canned JSON response so
 * the parsing logic is exercised without a real LLM call.
 */
class OnboardingCommandGenerationTest {

    /** Minimal stub backend — returns a fixed response for every request. */
    private static LlmBackend stubLlm(String responseText) {
        return new LlmBackend() {
            @Override public String id() { return "stub"; }
            @Override public LlmResponse complete(LlmRequest req) { return LlmResponse.text(responseText); }
        };
    }

    /** Stub backend that always throws. */
    private static final LlmBackend THROWING_LLM = new LlmBackend() {
        @Override public String id() { return "throwing"; }
        @Override public LlmResponse complete(LlmRequest req) { throw new RuntimeException("LLM unavailable"); }
    };

    /** A completed OnboardingFlow — all answers provided, ready for generateCommands(). */
    private OnboardingFlow completedFlow;

    @BeforeEach
    void setUp() {
        completedFlow = new OnboardingFlow();
        completedFlow.start();
        LlmBackend noopLlm = stubLlm("system-prompt");
        String noopMeta = "Generate a prompt for: {profile}";
        for (int i = 0; i < completedFlow.totalSteps() - 1; i++) {
            completedFlow.answer("answer " + i, noopLlm, noopMeta);
        }
        // Last answer triggers Done
        completedFlow.answer("last answer", noopLlm, noopMeta);
    }

    // ── Parsing happy path ────────────────────────────────────────────────────

    @Test
    void parsesCleanJsonArray() {
        LlmBackend stub = stubLlm("""
                [
                  {"trigger": "/core", "description": "Java Core practice"},
                  {"trigger": "/reactive", "description": "Reactive programming deep dive"},
                  {"trigger": "/database", "description": "Database design questions"}
                ]
                """);

        List<OnboardingFlow.GeneratedCommand> cmds = completedFlow.generateCommands(stub, "{profile}");

        assertThat(cmds).hasSize(3);
        assertThat(cmds.get(0).trigger()).isEqualTo("/core");
        assertThat(cmds.get(0).description()).isEqualTo("Java Core practice");
        assertThat(cmds.get(2).trigger()).isEqualTo("/database");
    }

    @Test
    void stripsMarkdownCodeFence() {
        LlmBackend stub = stubLlm("""
                ```json
                [{"trigger": "/algorithms", "description": "Algorithm questions"}]
                ```
                """);

        List<OnboardingFlow.GeneratedCommand> cmds = completedFlow.generateCommands(stub, "{profile}");

        assertThat(cmds).hasSize(1);
        assertThat(cmds.get(0).trigger()).isEqualTo("/algorithms");
    }

    @Test
    void stripsLeadingTextBeforeArray() {
        LlmBackend stub = stubLlm(
                "Here are the commands:\n" +
                "[{\"trigger\": \"/design\", \"description\": \"System design\"}]");

        List<OnboardingFlow.GeneratedCommand> cmds = completedFlow.generateCommands(stub, "{profile}");

        assertThat(cmds).hasSize(1);
        assertThat(cmds.get(0).trigger()).isEqualTo("/design");
    }

    // ── Trigger normalisation ─────────────────────────────────────────────────

    @Test
    void addsMissingSlashPrefix() {
        LlmBackend stub = stubLlm(
                "[{\"trigger\": \"core\", \"description\": \"Java Core\"}]");

        List<OnboardingFlow.GeneratedCommand> cmds = completedFlow.generateCommands(stub, "{profile}");

        assertThat(cmds).hasSize(1);
        assertThat(cmds.get(0).trigger()).isEqualTo("/core");
    }

    @Test
    void normalisesUpperCaseAndSpecialChars() {
        LlmBackend stub = stubLlm(
                "[{\"trigger\": \"/Java-Core!\", \"description\": \"Java Core\"}]");

        List<OnboardingFlow.GeneratedCommand> cmds = completedFlow.generateCommands(stub, "{profile}");

        assertThat(cmds).hasSize(1);
        assertThat(cmds.get(0).trigger()).isEqualTo("/java_core_");
    }

    @Test
    void capsAtSevenCommands() {
        String json = "[" +
                "{\"trigger\":\"/a\",\"description\":\"A\"}," +
                "{\"trigger\":\"/b\",\"description\":\"B\"}," +
                "{\"trigger\":\"/c\",\"description\":\"C\"}," +
                "{\"trigger\":\"/d\",\"description\":\"D\"}," +
                "{\"trigger\":\"/e\",\"description\":\"E\"}," +
                "{\"trigger\":\"/f\",\"description\":\"F\"}," +
                "{\"trigger\":\"/g\",\"description\":\"G\"}," +
                "{\"trigger\":\"/h\",\"description\":\"H\"}" +
                "]";
        LlmBackend stub = stubLlm(json);

        List<OnboardingFlow.GeneratedCommand> cmds = completedFlow.generateCommands(stub, "{profile}");

        assertThat(cmds).hasSize(7);
    }

    // ── Failure resilience ────────────────────────────────────────────────────

    @Test
    void returnsEmptyListOnBlankResponse() {
        List<OnboardingFlow.GeneratedCommand> cmds =
                completedFlow.generateCommands(stubLlm("  "), "{profile}");

        assertThat(cmds).isEmpty();
    }

    @Test
    void returnsEmptyListOnInvalidJson() {
        List<OnboardingFlow.GeneratedCommand> cmds =
                completedFlow.generateCommands(stubLlm("not json at all"), "{profile}");

        assertThat(cmds).isEmpty();
    }

    @Test
    void returnsEmptyListOnLlmException() {
        List<OnboardingFlow.GeneratedCommand> cmds =
                completedFlow.generateCommands(THROWING_LLM, "{profile}");

        assertThat(cmds).isEmpty();
    }

}
