package dev.coachbot.llm.ollama;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.output.Response;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM backend backed by Ollama (local models: Gemma, Llama, Mistral, …).
 * Enabled when {@code bot.llm.ollama.enabled=true}.
 *
 * <p>Ollama is inherently proxy-friendly — there is no API key by design.
 * {@code base-url} can point at any Ollama-compatible endpoint, including
 * an OneCLI or LiteLLM proxy layer.
 *
 * <p>Run locally:
 * <pre>
 * docker compose --profile local-llm up ollama
 * docker compose exec ollama ollama pull gemma3:12b
 * </pre>
 */
@Component
@ConditionalOnProperty(prefix = "bot.llm.ollama", name = "enabled", havingValue = "true")
public class OllamaBackend implements LlmBackend {

    private static final Logger log = LoggerFactory.getLogger(OllamaBackend.class);

    private final ChatLanguageModel model;

    public OllamaBackend(
            @Value("${bot.llm.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${bot.llm.ollama.model:gemma3:12b}") String modelName,
            @Value("${bot.llm.ollama.timeout-seconds:300}") int timeoutSeconds) {

        this.model = OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();

        log.info("Ollama backend ready (baseUrl={} model={} timeout={}s)", baseUrl, modelName, timeoutSeconds);
    }

    @Override
    public String id() {
        return "ollama";
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        List<ChatMessage> messages = buildMessages(request);
        Response<AiMessage> response = model.generate(messages);
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
