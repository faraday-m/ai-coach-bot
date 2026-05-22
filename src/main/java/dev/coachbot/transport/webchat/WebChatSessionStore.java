package dev.coachbot.transport.webchat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the live SSE emitters for active web-chat sessions.
 *
 * <p>Key: {@code sessionToken} (a client-generated UUID stored in browser localStorage).
 * Each open browser tab registers one emitter; the emitter is removed automatically
 * on timeout, completion, or failed send.
 */
@Component
public class WebChatSessionStore {

    private static final Logger log = LoggerFactory.getLogger(WebChatSessionStore.class);

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * Registers a new emitter for {@code sessionToken}.
     * If an old emitter exists for the same token (e.g. browser reconnected), it is
     * completed and replaced.
     */
    public void register(String sessionToken, SseEmitter emitter) {
        SseEmitter old = emitters.put(sessionToken, emitter);
        if (old != null) {
            try { old.complete(); } catch (Exception ignored) {}
        }
        emitter.onCompletion(() -> {
            emitters.remove(sessionToken, emitter);
            log.debug("[webchat] SSE completed for session {}", sessionToken);
        });
        emitter.onTimeout(() -> {
            emitters.remove(sessionToken, emitter);
            log.debug("[webchat] SSE timed out for session {}", sessionToken);
        });
        emitter.onError(ex -> {
            emitters.remove(sessionToken, emitter);
            log.debug("[webchat] SSE error for session {}: {}", sessionToken, ex.getMessage());
        });
        log.debug("[webchat] SSE registered for session {}", sessionToken);
    }

    /** Returns the emitter for this session, if it is still open. */
    public Optional<SseEmitter> get(String sessionToken) {
        return Optional.ofNullable(emitters.get(sessionToken));
    }

    /** Forcibly removes an emitter (e.g. after a send failure). */
    public void remove(String sessionToken) {
        emitters.remove(sessionToken);
    }

    /** Number of currently connected sessions (for monitoring). */
    public int size() {
        return emitters.size();
    }
}
