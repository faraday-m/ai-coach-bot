package dev.coachbot.transport.webchat;

import dev.coachbot.core.CommandRepository;
import dev.coachbot.core.HistoryStore;
import dev.coachbot.core.Orchestrator;
import dev.coachbot.core.UserIdentityStore;
import dev.coachbot.llm.ConversationMessage;
import dev.coachbot.transport.InboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;

/**
 * REST + SSE endpoints for the React web-chat frontend.
 *
 * <p>Base path: {@code /api/chat/{agentId}}
 *
 * <table>
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>POST</td><td>/message</td><td>Send a message; dispatches to GroupSession</td></tr>
 *   <tr><td>GET</td><td>/stream</td><td>Open SSE stream for bot replies</td></tr>
 *   <tr><td>GET</td><td>/history</td><td>Load conversation history</td></tr>
 *   <tr><td>GET</td><td>/commands</td><td>List available slash-commands</td></tr>
 * </table>
 *
 * <p>Enabled only when {@code bot.transports.webchat.enabled=true}.
 */
@RestController
@RequestMapping("/api/chat/{agentId}")
@ConditionalOnProperty(prefix = "bot.transports.webchat", name = "enabled", havingValue = "true")
public class WebChatController {

    private static final Logger log = LoggerFactory.getLogger(WebChatController.class);

    /** SSE timeout: 5 minutes.  The browser reconnects automatically on timeout. */
    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1_000L;

    /**
     * Built-in system commands handled by {@link dev.coachbot.core.GroupSession}
     * for every agent.  Always included first in the command picker.
     */
    private static final List<CommandDto> SYSTEM_COMMANDS = List.of(
            new CommandDto("/wiki",         "Save conversation to notes  (e.g. /wiki notes/today)"),
            new CommandDto("/memory",       "View your learning memory"),
            new CommandDto("/memory add",   "Add a note to memory  (e.g. /memory add Focus on Kafka)"),
            new CommandDto("/memory reset", "Clear all memory for this agent"),
            new CommandDto("/onboard",      "Restart the onboarding questionnaire")
    );

    private final Orchestrator         orchestrator;
    private final WebChatSessionStore  sessionStore;
    private final HistoryStore         historyStore;
    private final UserIdentityStore    identityStore;
    private final CommandRepository    commandRepository;

    public WebChatController(Orchestrator orchestrator,
                              WebChatSessionStore sessionStore,
                              HistoryStore historyStore,
                              UserIdentityStore identityStore,
                              CommandRepository commandRepository) {
        this.orchestrator      = orchestrator;
        this.sessionStore      = sessionStore;
        this.historyStore      = historyStore;
        this.identityStore     = identityStore;
        this.commandRepository = commandRepository;
    }

    // ── POST /message ─────────────────────────────────────────────────────────

    /**
     * Accepts a chat message from the browser and dispatches it asynchronously
     * to the target agent's {@link dev.coachbot.core.GroupSession}.
     *
     * <p>The response arrives later via the SSE stream (GET /stream).
     *
     * @param agentId       Agent identifier from the URL path.
     * @param request       JSON body: {@code {text, sessionToken}}.
     */
    @PostMapping("/message")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void sendMessage(@PathVariable String agentId,
                            @RequestBody MessageRequest request) {
        var msg = new InboundMessage(
                "webchat",
                request.sessionToken(),   // chatId = token → used to route reply back via SSE
                request.sessionToken(),   // senderId = token → resolved to canonical user ID
                "Web User",
                request.text(),
                Instant.now()
        );
        log.debug("[webchat] POST /message agentId={} session={}… text='{}'",
                agentId, request.sessionToken().substring(0, Math.min(8, request.sessionToken().length())),
                request.text().length() > 80 ? request.text().substring(0, 80) + "…" : request.text());

        orchestrator.dispatch(agentId, msg);
    }

    // ── GET /stream ───────────────────────────────────────────────────────────

    /**
     * Opens a Server-Sent Events stream for the given session.
     * The browser keeps this connection open; bot replies and typing indicators
     * are pushed as named events:
     * <ul>
     *   <li>{@code event: message} — bot reply text (UTF-8, may be multi-line)</li>
     *   <li>{@code event: typing}  — empty payload; show typing indicator</li>
     * </ul>
     *
     * <p>On reconnect the browser will re-open this endpoint with the same token.
     *
     * @param sessionToken Client-generated UUID (stored in browser localStorage).
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String agentId,
                              @RequestParam String sessionToken) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        sessionStore.register(sessionToken, emitter);
        log.debug("[webchat] SSE stream opened: agentId={} session={}…",
                agentId, sessionToken.substring(0, Math.min(8, sessionToken.length())));
        return emitter;
    }

    // ── GET /history ──────────────────────────────────────────────────────────

    /**
     * Returns the last {@code limit} conversation messages for this session,
     * oldest first.  Useful for re-populating the UI after a page refresh.
     */
    @GetMapping("/history")
    public List<MessageDto> history(@PathVariable String agentId,
                                     @RequestParam String sessionToken,
                                     @RequestParam(defaultValue = "50") int limit) {
        // Use the same canonical-ID resolution as GroupSession so history matches
        String canonicalId = identityStore.resolve("webchat", sessionToken);
        return historyStore.load(agentId, canonicalId, limit).stream()
                .filter(m -> m.role() != ConversationMessage.Role.TOOL_RESULT)   // internal only
                .map(m -> new MessageDto(
                        m.role() == ConversationMessage.Role.USER ? "user" : "assistant",
                        m.content()))
                .toList();
    }

    // ── GET /commands ─────────────────────────────────────────────────────────

    /**
     * Returns the enabled slash-commands for this agent.
     * Used by the React frontend's command picker (triggered when the user types "/").
     */
    @GetMapping("/commands")
    public List<CommandDto> commands(@PathVariable String agentId) {
        // Agent-specific commands (defined in the DB via admin UI)
        List<CommandDto> agentCommands = commandRepository.findEnabledByAgent(agentId).stream()
                .map(c -> new CommandDto(c.trigger(), c.description()))
                .toList();

        // System commands come first; agent-specific commands follow
        List<CommandDto> all = new java.util.ArrayList<>(SYSTEM_COMMANDS);
        all.addAll(agentCommands);
        return all;
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record MessageRequest(String text, String sessionToken) {}
    public record MessageDto(String role, String content) {}
    public record CommandDto(String trigger, String description) {}
}
