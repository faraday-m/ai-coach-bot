package dev.coachbot.transport.webchat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class WebChatTransportTest {

    private WebChatSessionStore store;
    private WebChatTransport    transport;

    @BeforeEach
    void setUp() {
        store     = new WebChatSessionStore();
        transport = new WebChatTransport(store);
    }

    @Test
    void id_returnsWebchat() {
        assertThat(transport.id()).isEqualTo("webchat");
    }

    @Test
    void isConnected_alwaysTrue() {
        assertThat(transport.isConnected()).isTrue();
    }

    @Test
    void send_doesNotThrow_whenNoEmitter() {
        // No emitter registered — should silently drop the message
        assertThatCode(() -> transport.send("unknown-session", "hello"))
                .doesNotThrowAnyException();
    }

    @Test
    void send_doesNotThrow_whenEmitterAlreadyCompleted() {
        // SseEmitter.complete() marks it as done; the next send() will throw
        // IllegalStateException — WebChatTransport must catch it silently.
        SseEmitter expired = new SseEmitter();
        store.register("tok1", expired);
        expired.complete();   // simulate browser disconnect

        // send() must not propagate the IllegalStateException
        assertThatCode(() -> transport.send("tok1", "hi")).doesNotThrowAnyException();
    }

    @Test
    void registerCommands_isNoOp() {
        // WebChatTransport ignores registerCommands — commands served via REST
        assertThatCode(() -> transport.registerCommands("tok1",
                List.of(new WebChatTransport.CommandEntry("/quiz", "Ask a question"))))
                .doesNotThrowAnyException();
    }

    // ── WebChatSessionStore ────────────────────────────────────────────────────

    @Test
    void store_register_replacesOldEmitter() {
        SseEmitter first  = new SseEmitter();
        SseEmitter second = new SseEmitter();

        store.register("tok", first);
        assertThat(store.get("tok")).contains(first);

        store.register("tok", second);   // replaces first
        assertThat(store.get("tok")).contains(second);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void store_remove_clearsEmitter() {
        store.register("tok", new SseEmitter());
        assertThat(store.size()).isEqualTo(1);

        store.remove("tok");
        assertThat(store.get("tok")).isEmpty();
        assertThat(store.size()).isEqualTo(0);
    }

    @Test
    void store_explicitRemove_clearsEmitter() {
        // The onCompletion callback requires servlet infrastructure and doesn't fire
        // in unit tests. Test the explicit remove() path instead.
        SseEmitter emitter = new SseEmitter();
        store.register("tok2", emitter);
        assertThat(store.size()).isEqualTo(1);

        store.remove("tok2");
        assertThat(store.get("tok2")).isEmpty();
        assertThat(store.size()).isEqualTo(0);
    }
}
