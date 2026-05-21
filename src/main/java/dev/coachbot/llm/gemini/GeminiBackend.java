package dev.coachbot.llm.gemini;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
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
 * LLM backend backed by Google Gemini (official API key required).
 * Enabled when {@code bot.llm.gemini.enabled=true}.
 *
 * <h2>Credential modes (first match wins)</h2>
 * <ol>
 *   <li><b>Direct</b>   — {@code bot.llm.gemini.api-key: ${GEMINI_API_KEY}}</li>
 *   <li><b>Provider</b> — {@code bot.llm.gemini.credential-provider: vault|onecli|…}</li>
 * </ol>
 *
 * <p>Get a free API key from
 * <a href="https://aistudio.google.com/app/apikey">Google AI Studio</a>.
 * Free tier includes generous quotas for Gemini Flash and Gemini Pro.
 *
 * <h2>Recommended models</h2>
 * <ul>
 *   <li>{@code gemini-2.0-flash}    — fast, cheap, very capable (default)</li>
 *   <li>{@code gemini-1.5-pro}      — long context (up to 2M tokens)</li>
 *   <li>{@code gemini-2.5-pro-preview-05-06} — best reasoning, slower</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "bot.llm.gemini", name = "enabled", havingValue = "true")
public class GeminiBackend implements LlmBackend {

    private static final Logger log = LoggerFactory.getLogger(GeminiBackend.class);

    private final ChatLanguageModel model;

    public GeminiBackend(
            @Value("${bot.llm.gemini.api-key:}") String inlineApiKey,
            @Value("${bot.llm.gemini.api-key-name:GEMINI_API_KEY}") String apiKeyName,
            @Value("${bot.llm.gemini.credential-provider:env}") String credProviderName,
            @Value("${bot.llm.gemini.model:gemini-2.0-flash}") String modelName,
            @Value("${bot.llm.gemini.max-output-tokens:4096}") int maxOutputTokens,
            CredentialResolver credentialResolver) {

        // Gemini does not support a proxy base-url override in LangChain4j — API key is required.
        Optional<String> resolvedKey = credentialResolver.resolve(
                inlineApiKey, apiKeyName, credProviderName, /* baseUrl= */ "", id());

        this.model = GoogleAiGeminiChatModel.builder()
                .apiKey(resolvedKey.orElseThrow())
                .modelName(modelName)
                .maxOutputTokens(maxOutputTokens)
                .build();

        log.info("Gemini backend ready (model={})", modelName);
    }

    @Override
    public String id() { return "gemini"; }

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
