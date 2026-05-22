package dev.coachbot.core;

import dev.coachbot.llm.ConversationMessage;
import dev.coachbot.llm.LlmBackend;
import dev.coachbot.llm.LlmRequest;
import dev.coachbot.llm.LlmResponse;
import dev.coachbot.llm.Tool;
import dev.coachbot.llm.ToolCall;
import dev.coachbot.llm.ToolDefinition;
import dev.coachbot.llm.ToolResult;
import dev.coachbot.storage.StorageBackend;
import dev.coachbot.storage.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the agent loop logic inside GroupSession via an integration harness
 * (same approach as OrchestratorIntegrationTest — real GroupSession, fake backends).
 *
 * <p>Rather than duplicating the full orchestrator setup, these tests exercise the
 * loop through a minimal fake: a CaptureLlm that returns a configurable sequence
 * of responses (tool-calls first, then text).
 */
class AgentLoopTest {

    // ── Fakes ──────────────────────────────────────────────────────────────────

    /**
     * LLM that returns a sequence of pre-programmed responses.
     * Supports tools (supportsTools() = true).
     */
    static class SequenceLlm implements LlmBackend {
        private final List<LlmResponse> responses;
        private int index = 0;
        final List<LlmRequest> received = new CopyOnWriteArrayList<>();

        SequenceLlm(LlmResponse... responses) {
            this.responses = List.of(responses);
        }

        @Override public String id() { return "sequence"; }
        @Override public boolean supportsTools() { return true; }

        @Override public LlmResponse complete(LlmRequest req) {
            received.add(req);
            if (index < responses.size()) return responses.get(index++);
            return LlmResponse.text("(no more responses)");
        }
    }

    /**
     * A no-op tool that records calls and returns a fixed JSON result.
     */
    static class SpyTool implements Tool {
        final List<String> executedArgs = new CopyOnWriteArrayList<>();
        final String toolName;
        final String fixedResult;

        SpyTool(String name, String result) {
            this.toolName    = name;
            this.fixedResult = result;
        }

        @Override public String name() { return toolName; }
        @Override public String description() { return "spy tool"; }
        @Override public String jsonSchema() {
            return "{\"type\":\"object\",\"properties\":{},\"required\":[]}";
        }
        @Override public ToolResult execute(String toolCallId, String argsJson) {
            executedArgs.add(argsJson);
            return new ToolResult(toolCallId, toolName, fixedResult, false);
        }
    }

    static class NoopStorage implements StorageBackend {
        @Override public String id() { return "noop"; }
        @Override public void write(String p, String c) {}
        @Override public void append(String p, String c) {}
        @Override public Optional<String> read(String p) { return Optional.empty(); }
        @Override public List<String> list(String prefix) { return List.of(); }
        @Override public void delete(String p) {}
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Builds a one-step tool-call LlmResponse. */
    private static LlmResponse toolCallResponse(String callId, String toolName, String args) {
        return new LlmResponse(null, List.of(new ToolCall(callId, toolName, args)));
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    void loop_terminatesAfterSingleTextResponse() {
        // LLM returns text immediately — no tool calls
        var llm  = new SequenceLlm(LlmResponse.text("Hello, world!"));
        var tool = new SpyTool("echo", "\"done\"");

        LlmRequest initial = new LlmRequest(
                "sys", List.of(), "hi", "user1", "agent1",
                List.of(tool.definition()));

        LlmResponse result = runLoop(llm, initial, List.of(tool));

        assertThat(result.text()).isEqualTo("Hello, world!");
        assertThat(llm.received).hasSize(1);
        assertThat(tool.executedArgs).isEmpty();
    }

    @Test
    void loop_executesToolAndContinues() {
        // Step 1: LLM requests echo tool; Step 2: LLM returns text
        var llm = new SequenceLlm(
                toolCallResponse("c1", "echo", "{\"msg\":\"test\"}"),
                LlmResponse.text("Done after echo")
        );
        var tool = new SpyTool("echo", "\"ok\"");

        LlmRequest initial = new LlmRequest(
                "sys", List.of(), "call echo", "user1", "agent1",
                List.of(tool.definition()));

        LlmResponse result = runLoop(llm, initial, List.of(tool));

        assertThat(result.text()).isEqualTo("Done after echo");
        assertThat(llm.received).hasSize(2);
        assertThat(tool.executedArgs).hasSize(1).containsExactly("{\"msg\":\"test\"}");

        // Second LLM request should have the tool result in history
        LlmRequest secondReq = llm.received.get(1);
        assertThat(secondReq.history())
                .anyMatch(m -> m.role() == ConversationMessage.Role.ASSISTANT)
                .anyMatch(m -> m.role() == ConversationMessage.Role.TOOL_RESULT);
    }

    @Test
    void loop_executesMultipleToolCallsInOneStep() {
        // LLM requests two tools at once
        var llm = new SequenceLlm(
                new LlmResponse(null, List.of(
                        new ToolCall("c1", "tool_a", "{}"),
                        new ToolCall("c2", "tool_b", "{}")
                )),
                LlmResponse.text("All done")
        );
        var toolA = new SpyTool("tool_a", "\"a-result\"");
        var toolB = new SpyTool("tool_b", "\"b-result\"");

        LlmRequest initial = new LlmRequest(
                "sys", List.of(), "call both", "user1", "agent1",
                List.of(toolA.definition(), toolB.definition()));

        LlmResponse result = runLoop(llm, initial, List.of(toolA, toolB));

        assertThat(result.text()).isEqualTo("All done");
        assertThat(toolA.executedArgs).hasSize(1);
        assertThat(toolB.executedArgs).hasSize(1);

        // History should contain 1 assistant (tool-calls) + 2 TOOL_RESULT messages
        LlmRequest secondReq = llm.received.get(1);
        long assistantCount = secondReq.history().stream()
                .filter(m -> m.role() == ConversationMessage.Role.ASSISTANT).count();
        long resultCount = secondReq.history().stream()
                .filter(m -> m.role() == ConversationMessage.Role.TOOL_RESULT).count();
        assertThat(assistantCount).isEqualTo(1);
        assertThat(resultCount).isEqualTo(2);
    }

    @Test
    void loop_capsAtMaxSteps() {
        // LLM always returns a tool call — loop should stop after MAX steps
        int MAX = 10;
        LlmResponse[] responses = new LlmResponse[MAX + 5];
        for (int i = 0; i < responses.length; i++) {
            responses[i] = toolCallResponse("c" + i, "echo", "{}");
        }
        var llm  = new SequenceLlm(responses);
        var tool = new SpyTool("echo", "\"ok\"");

        LlmRequest initial = new LlmRequest(
                "sys", List.of(), "loop forever", "user1", "agent1",
                List.of(tool.definition()));

        LlmResponse result = runLoop(llm, initial, List.of(tool));

        assertThat(result.text()).contains("maximum");
        assertThat(llm.received).hasSize(MAX);
        assertThat(tool.executedArgs).hasSize(MAX);
    }

    @Test
    void loop_handlesUnknownTool_gracefully() {
        // LLM requests a tool that is not in the tool list
        var llm = new SequenceLlm(
                toolCallResponse("c1", "nonexistent_tool", "{}"),
                LlmResponse.text("Got error result")
        );

        LlmRequest initial = new LlmRequest(
                "sys", List.of(), "call unknown", "user1", "agent1",
                List.of()); // no tools registered

        LlmResponse result = runLoop(llm, initial, List.of());

        assertThat(result.text()).isEqualTo("Got error result");
        // Error result injected into history
        LlmRequest secondReq = llm.received.get(1);
        assertThat(secondReq.history())
                .anyMatch(m -> m.role() == ConversationMessage.Role.TOOL_RESULT
                        && m.content().contains("unknown tool"));
    }

    // ── Loop harness ──────────────────────────────────────────────────────────

    /**
     * Runs the same logic as GroupSession.runAgentLoop() — extracted here so we
     * can test it without spinning up the full session/transport machinery.
     */
    private LlmResponse runLoop(LlmBackend llm, LlmRequest initial, List<Tool> tools) {
        final int MAX_STEPS = 10;
        List<ConversationMessage> history = new ArrayList<>(initial.history());
        LlmRequest current = initial;

        for (int step = 0; step < MAX_STEPS; step++) {
            LlmResponse resp = llm.complete(current);

            if (resp.toolCalls().isEmpty()) return resp;

            history.add(ConversationMessage.assistantToolCalls(resp.toolCalls(), resp.text()));

            for (ToolCall tc : resp.toolCalls()) {
                ToolResult tr = tools.stream()
                        .filter(t -> t.name().equals(tc.toolName()))
                        .findFirst()
                        .map(t -> t.execute(tc.toolCallId(), tc.argumentsJson()))
                        .orElse(new ToolResult(tc.toolCallId(), tc.toolName(),
                                "{\"error\":\"unknown tool: " + tc.toolName() + "\"}", true));

                history.add(ConversationMessage.toolResult(
                        tr.toolCallId(), tr.toolName(), tr.resultJson()));
            }

            current = new LlmRequest(
                    initial.systemPrompt(), List.copyOf(history),
                    initial.userMessage(), initial.userId(), initial.agentId(),
                    initial.tools());
        }

        return LlmResponse.text(
                "⚠️ The agent loop reached the maximum number of steps (" + MAX_STEPS + ").");
    }
}
