package dev.coachbot.transport.webchat;

import dev.coachbot.transport.InboundMessage;
import dev.coachbot.transport.InboundMessageHandler;
import dev.coachbot.transport.TransportPlugin;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

/**
 * HTTP/SSE transport for the React web-chat frontend.
 *
 * <p>Unlike Telegram (long-polling) or Jabber (persistent TCP), this transport is
 * entirely driven by incoming HTTP requests — no background polling needed.
 * Messages arrive via {@code POST /api/chat/{agentId}/message} (handled by
 * {@link WebChatController}) and are dispatched directly to the target
 * {@link dev.coachbot.core.GroupSession} via
 * {@link dev.coachbot.core.Orchestrator#dispatch}.
 *
 * <p>Responses flow back to the browser over SSE:
 * <ul>
 *   <li>{@code event: message} — bot reply text</li>
 *   <li>{@code event: typing}  — typing indicator while the LLM is running</li>
 * </ul>
 *
 * <p>Enable with {@code BOT_WEBCHAT_ENABLED=true} in the environment / .env file.
 */
@Component
@ConditionalOnProperty(prefix = "bot.transports.webchat", name = "enabled", havingValue = "true")
public class WebChatTransport implements TransportPlugin {

    private static final Logger log = LoggerFactory.getLogger(WebChatTransport.class);

    private final WebChatSessionStore sessionStore;

    public WebChatTransport(WebChatSessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    @PostConstruct
    public void init() {
        log.info("WebChat transport ready — REST/SSE on /api/chat/**");
    }

    @Override
    public String id() { return "webchat"; }

    /**
     * No-op: messages arrive via HTTP, not pushed by the transport.
     * The {@code handler} reference is unused — routing is done by
     * {@link dev.coachbot.core.Orchestrator#dispatch} in the controller.
     */
    @Override
    public void start(InboundMessageHandler handler) {
        // intentionally empty — HTTP-driven, no polling thread needed
    }

    /**
     * Pushes a bot reply to the browser via SSE.
     *
     * @param chatId The session token (used as the SSE emitter key).
     * @param text   The reply text to deliver.
     */
    @Override
    public void send(String chatId, String text) {
        sessionStore.get(chatId).ifPresentOrElse(
                emitter -> sendEvent(emitter, chatId, "message", text),
                () -> log.debug("[webchat] No active SSE for session {} — reply dropped", chatId)
        );
    }

    /** Sends a lightweight typing-indicator event to the browser. */
    @Override
    public void sendTyping(String chatId) {
        sessionStore.get(chatId).ifPresent(
                emitter -> sendEvent(emitter, chatId, "typing", "")
        );
    }

    /**
     * No-op for webchat: commands are served on demand via
     * {@code GET /api/chat/{agentId}/commands} — no pre-registration needed.
     */
    @Override
    public void registerCommands(String chatId, List<CommandEntry> commands) {
        // REST endpoint reads directly from CommandRepository — nothing to do here
    }

    @Override
    public boolean isConnected() { return true; }

    @Override
    public void stop() {
        log.info("WebChat transport stopped");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void sendEvent(SseEmitter emitter, String sessionToken,
                            String eventName, String data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception e) {
            // IOException on network failure; IllegalStateException if emitter already completed
            log.debug("[webchat] SSE send failed for session {}: {}", sessionToken, e.getMessage());
            sessionStore.remove(sessionToken);
        }
    }
}
