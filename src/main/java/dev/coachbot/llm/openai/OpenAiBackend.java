package dev.coachbot.llm.openai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
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
 * LLM backend backed by OpenAI (or any OpenAI-compatible endpoint).
 * Enabled when {@code bot.llm.openai.enabled=true}.
 *
 * <h2>Credential modes (first match wins)</h2>
 * <ol>
 *   <li><b>Direct</b>   — {@code bot.llm.openai.api-key: ${OPENAI_API_KEY}}</li>
 *   <li><b>Provider</b> — {@code bot.llm.openai.credential-provider: vault|onecli|…}</li>
 *   <li><b>Proxy</b>    — {@code bot.llm.openai.base-url: http://…}; proxy injects auth.</li>
 * </ol>
 *
 * <h2>OpenAI-compatible endpoints</h2>
 * Setting {@code base-url} makes this backend work with any OpenAI-compatible API:
 * <ul>
 *   <li><b>Groq</b>        — {@code https://api.groq.com/openai/v1} (fast Llama/Mixtral)</li>
 *   <li><b>Together AI</b> — {@code https://api.together.xyz/v1}</li>
 *   <li><b>LM Studio</b>   — {@code http://localhost:1234/v1} (local, no key needed)</li>
 *   <li><b>Azure OpenAI</b>— set {@code base-url} + {@code api-version}</li>
 *   <li><b>OneCLI proxy</b>— transparent auth injection (no key in JVM)</li>
 * </ul>
 *
 * <p>Get API key from <a href="https://platform.openai.com/api-keys">platform.openai.com</a>
 * ({@code sk-…}).
 */
@Component
@ConditionalOnProperty(prefix = "bot.llm.openai", name = "enabled", havingValue = "true")
public class OpenAiBackend implements LlmBackend {

    private static final Logger log = LoggerFactory.getLogger(OpenAiBackend.class);

    private final ChatLanguageModel model;

    public OpenAiBackend(
            @Value("${bot.llm.openai.api-key:}") String inlineApiKey,
            @Value("${bot.llm.openai.base-url:}") String baseUrl,
            @Value("${bot.llm.openai.api-key-name:OPENAI_API_KEY}") String apiKeyName,
            @Value("${bot.llm.openai.credential-provider:env}") String credProviderName,
            @Value("${bot.llm.openai.model:gpt-4o}") String modelName,
            @Value("${bot.llm.openai.max-tokens:4096}") int maxTokens,
            CredentialResolver credentialResolver) {

        Optional<String> resolvedKey = credentialResolver.resolve(
                inlineApiKey, apiKeyName, credProviderName, baseUrl, id());

        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .modelName(modelName)
                .maxTokens(maxTokens);

        if (StringUtils.hasText(baseUrl)) {
            // OpenAI-compatible proxy or alternative endpoint (Groq, Together AI, LM Studio…)
            builder.baseUrl(baseUrl);
            // Use resolved key if available (e.g. Groq), or a dummy for keyless local endpoints
            builder.apiKey(resolvedKey.orElse("no-key-needed"));
            log.info("OpenAI backend → CUSTOM endpoint (url={} model={})", baseUrl, modelName);
        } else {
            builder.apiKey(resolvedKey.orElseThrow());
            log.info("OpenAI backend ready (model={})", modelName);
        }

        this.model = builder.build();
    }

    @Override
    public String id() { return "openai"; }

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
