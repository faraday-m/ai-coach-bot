package dev.coachbot.llm.claude;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.coachbot.credentials.CredentialResolver;
import dev.coachbot.llm.ConversationMessage;
import dev.coachbot.llm.LlmBackend;
import dev.coachbot.llm.LlmRequest;
import dev.coachbot.llm.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
 * <p>Use the official API key from
 * <a href="https://console.anthropic.com/settings/keys">console.anthropic.com</a>
 * ({@code sk-ant-api03-…}). OAuth / user tokens are not supported here by design.
 */
@Component
@ConditionalOnProperty(prefix = "bot.llm.claude", name = "enabled", havingValue = "true")
public class ClaudeBackend implements LlmBackend {

    private static final Logger log = LoggerFactory.getLogger(ClaudeBackend.class);

    private final ChatLanguageModel model;

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

        AnthropicChatModel.AnthropicChatModelBuilder builder = AnthropicChatModel.builder()
                .modelName(modelName)
                .maxTokens(maxTokens);

        if (StringUtils.hasText(baseUrl)) {
            builder.baseUrl(baseUrl).apiKey("proxy");
            log.info("Claude backend → PROXY mode ({})", baseUrl);
        } else {
            builder.apiKey(resolvedKey.orElseThrow());
            log.info("Claude backend ready (model={})", modelName);
        }

        this.model = builder.build();
    }

    @Override
    public String id() { return "claude"; }

    @Override
    public LlmResponse complete(LlmRequest request) {
        Response<AiMessage> response = model.generate(buildMessages(request));
        return new LlmResponse(response.content().text());
    }

    private List<ChatMessage> buildMessages(LlmRequest request) {
        List<ChatMessage> messages = new ArrayList<>();
        if (StringUtils.hasText(request.systemPrompt())) {
            messages.add(SystemMessage.from(request.systemPrompt()));
        }
        for (ConversationMessage m : request.history()) {
            messages.add(switch (m.role()) {
                case USER      -> UserMessage.from(m.content());
                case ASSISTANT -> AiMessage.from(m.content());
            });
        }
        messages.add(UserMessage.from(request.userMessage()));
        return messages;
    }
}
