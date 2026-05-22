package dev.coachbot.llm.claude;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolParameters;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.coachbot.credentials.CredentialResolver;
import dev.coachbot.llm.ConversationMessage;
import dev.coachbot.llm.LlmBackend;
import dev.coachbot.llm.LlmRequest;
import dev.coachbot.llm.LlmResponse;
import dev.coachbot.llm.ToolCall;
import dev.coachbot.llm.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/**
 * LLM backend backed by Anthropic Claude (official API key required).
 * Enabled when {@code bot.llm.claude.enabled=true}.
 *
 * <h2>Credential modes (first match wins)</h2>
 * <ol>
 *   <li><b>Direct</b>   — {@code bot.llm.claude.api-key: ${ANTHROPIC_API_KEY}}</li>
 *   <li><b>Provider</b> — {@code bot.llm.claude.credential-provider: vault|onecli|…}</li>
 *   <li><b>Proxy</b>    — {@code bot.llm.claude.base-url: http://…} (OneCLI, LiteLLM, …);
 *       the proxy injects credentials, no key in this JVM.</li>
 * </ol>
 *
 * <p>Supports native tool use via LangChain4j's {@link ToolSpecification} API.
 * Token-by-token streaming via {@link AnthropicStreamingChatModel}.
 */
@Component
@ConditionalOnProperty(prefix = "bot.llm.claude", name = "enabled", havingValue = "true")
public class ClaudeBackend implements LlmBackend {

    private static final Logger log = LoggerFactory.getLogger(ClaudeBackend.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Marker prefix in ASSISTANT history messages that carry serialised tool calls. */
    static final String TOOL_CALLS_MARKER = "{\"__tc\":";

    private final ChatLanguageModel model;
    private final StreamingChatLanguageModel streamingModel;

    public ClaudeBackend(
            @Value("${bot.llm.claude.api-key:}") String inlineApiKey,
            @Value("${bot.llm.claude.base-url:}") String baseUrl,
            @Value("${bot.llm.claude.api-key-name:ANTHROPIC_API_KEY}") String apiKeyName,
            @Value("${bot.llm.claude.credential-provider:env}") String credProviderName,
            @Value("${bot.llm.claude.model:claude-opus-4-5}") String modelName,
            @Value("${bot.llm.claude.max-tokens:4096}") int maxTokens,
            CredentialResolver credentialResolver) {

        Optional<String> resolvedKey = credentialResolver.resolve(
                inlineApiKey, apiKeyName, credProviderName, baseUrl, id());

        AnthropicChatModel.AnthropicChatModelBuilder batchBuilder = AnthropicChatModel.builder()
                .modelName(modelName)
                .maxTokens(maxTokens);

        AnthropicStreamingChatModel.AnthropicStreamingChatModelBuilder streamBuilder =
                AnthropicStreamingChatModel.builder()
                .modelName(modelName)
                .maxTokens(maxTokens);

        if (StringUtils.hasText(baseUrl)) {
            batchBuilder.baseUrl(baseUrl).apiKey("proxy");
            streamBuilder.baseUrl(baseUrl).apiKey("proxy");
            log.info("Claude backend → PROXY mode ({})", baseUrl);
        } else {
            String key = resolvedKey.orElseThrow();
            batchBuilder.apiKey(key);
            streamBuilder.apiKey(key);
            log.info("Claude backend ready (model={})", modelName);
        }

        this.model          = batchBuilder.build();
        this.streamingModel = streamBuilder.build();
    }

    @Override
    public String id() { return "claude"; }

    @Override
    public boolean supportsTools() { return true; }

    // ── complete ──────────────────────────────────────────────────────────────

    @Override
    public LlmResponse complete(LlmRequest request) {
        List<ChatMessage> messages = buildMessages(request);

        if (request.tools().isEmpty()) {
            Response<AiMessage> response = model.generate(messages);
            return LlmResponse.text(response.content().text());
        }

        // Tool use path
        List<ToolSpecification> specs = request.tools().stream()
                .map(ClaudeBackend::toToolSpec)
                .toList();

        Response<AiMessage> response = model.generate(messages, specs);
        AiMessage aiMessage = response.content();

        if (aiMessage.hasToolExecutionRequests()) {
            List<ToolCall> calls = aiMessage.toolExecutionRequests().stream()
                    .map(r -> new ToolCall(r.id(), r.name(), r.arguments()))
                    .toList();
            // Text may accompany tool calls (Claude's "thinking" text) — preserve it
            return new LlmResponse(aiMessage.text(), calls);
        }

        return LlmResponse.text(aiMessage.text());
    }

    // ── stream ────────────────────────────────────────────────────────────────

    @Override
    public void stream(LlmRequest request, Consumer<String> tokenSink) {
        List<ChatMessage> messages = buildMessages(request);
        CountDownLatch done = new CountDownLatch(1);

        streamingModel.generate(messages, new dev.langchain4j.model.StreamingResponseHandler<>() {
            @Override public void onNext(String token) { tokenSink.accept(token); }
            @Override public void onComplete(Response<AiMessage> r) { done.countDown(); }
            @Override public void onError(Throwable t) {
                log.warn("[claude] Streaming error: {}", t.getMessage());
                tokenSink.accept("[error: " + t.getMessage() + "]");
                done.countDown();
            }
        });

        try { done.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ── buildMessages ─────────────────────────────────────────────────────────

    private List<ChatMessage> buildMessages(LlmRequest request) {
        List<ChatMessage> messages = new ArrayList<>();
        if (StringUtils.hasText(request.systemPrompt())) {
            messages.add(SystemMessage.from(request.systemPrompt()));
        }
        for (ConversationMessage m : request.history()) {
            messages.add(switch (m.role()) {
                case USER      -> UserMessage.from(m.content());
                case ASSISTANT -> parseAssistantMessage(m.content());
                case TOOL_RESULT -> parseToolResultMessage(m.content());
            });
        }
        messages.add(UserMessage.from(request.userMessage()));
        return messages;
    }

    /**
     * Converts an ASSISTANT ConversationMessage to a LangChain4j ChatMessage.
     * If the content starts with {@link #TOOL_CALLS_MARKER} it is a serialised
     * tool-call turn; otherwise it is a plain text response.
     */
    private static ChatMessage parseAssistantMessage(String content) {
        if (content != null && content.startsWith(TOOL_CALLS_MARKER)) {
            try {
                JsonNode root = MAPPER.readTree(content);
                JsonNode calls = root.get("__tc");
                List<ToolExecutionRequest> requests = new ArrayList<>();
                for (JsonNode c : calls) {
                    requests.add(ToolExecutionRequest.builder()
                            .id(c.get("id").asText())
                            .name(c.get("name").asText())
                            .arguments(c.get("args").asText())
                            .build());
                }
                // text field may be null
                String text = root.has("text") ? root.get("text").asText(null) : null;
                return StringUtils.hasText(text)
                        ? AiMessage.from(text, requests)
                        : AiMessage.from(requests);
            } catch (Exception e) {
                log.warn("[claude] Failed to parse tool-call history entry — falling back to text", e);
            }
        }
        return AiMessage.from(content);
    }

    /**
     * Converts a TOOL_RESULT ConversationMessage to a LangChain4j
     * {@link ToolExecutionResultMessage}.
     * Expected content format (set by {@link ConversationMessage#toolResult}):
     * {@code {"tool_call_id":"…","tool":"…","result":…}}
     */
    private static ChatMessage parseToolResultMessage(String content) {
        try {
            JsonNode node = MAPPER.readTree(content);
            String id       = node.get("tool_call_id").asText();
            String toolName = node.get("tool").asText();
            // "result" may be any JSON value — convert back to string for the API
            String result   = node.get("result").toString();
            return ToolExecutionResultMessage.from(id, toolName, result);
        } catch (Exception e) {
            log.warn("[claude] Failed to parse TOOL_RESULT history — injecting as user message", e);
            return UserMessage.from(content);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Converts a {@link ToolDefinition} (our backend-agnostic SPI) to a
     * LangChain4j {@link ToolSpecification} for the Anthropic API.
     *
     * <p>The {@code jsonSchema} string is expected to be a JSON object schema:
     * <pre>
     * {"type":"object","properties":{…},"required":[…]}
     * </pre>
     */
    @SuppressWarnings("unchecked")
    private static ToolSpecification toToolSpec(ToolDefinition def) {
        try {
            Map<String, Object> schema = MAPPER.readValue(def.jsonSchema(),
                    new TypeReference<>() {});
            Map<String, Map<String, Object>> props =
                    (Map<String, Map<String, Object>>) schema.getOrDefault("properties", Map.of());
            List<String> required =
                    (List<String>) schema.getOrDefault("required", List.of());

            return ToolSpecification.builder()
                    .name(def.name())
                    .description(def.description())
                    .parameters(ToolParameters.builder()
                            .type("object")
                            .properties(props)
                            .required(required)
                            .build())
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Invalid JSON schema for tool '" + def.name() + "'", e);
        }
    }
}
