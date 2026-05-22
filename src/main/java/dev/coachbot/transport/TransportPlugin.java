package dev.coachbot.transport;

import dev.coachbot.plugin.Identifiable;

import java.util.List;

/**
 * SPI for messaging transports (Telegram, XMPP, console, …).
 *
 * <p>To add a new transport:
 * <ol>
 *   <li>Implement this interface.</li>
 *   <li>Annotate with {@code @Component} (+ {@code @ConditionalOnProperty} if optional).</li>
 *   <li>Reference the transport by {@link #id()} in {@code application.yml} seeds.</li>
 * </ol>
 */
public interface TransportPlugin extends Identifiable {

    /**
     * A slash-command entry to be surfaced in the transport's native command UI
     * (e.g. Telegram's "/" menu, a React frontend command picker).
     *
     * @param trigger     The slash-command trigger, e.g. {@code /quiz} or {@code /hint}.
     * @param description Short human-readable description shown to the user.
     */
    record CommandEntry(String trigger, String description) {}

    /**
     * Starts the transport and registers the inbound message callback.
     * Must be non-blocking — long-running I/O should use a background thread.
     */
    void start(InboundMessageHandler handler);

    /** Sends a text message to the specified chat. */
    void send(String chatId, String text);

    /** Optional: signal typing indicator while the LLM is thinking. */
    default void sendTyping(String chatId) {}

    /**
     * Registers (or updates) the visible command list for a specific chat.
     * Implementations that support native command UIs (Telegram, React) override this;
     * others silently ignore the call.
     *
     * <p>Called by the {@link dev.coachbot.core.Orchestrator} on startup and whenever
     * an agent's command list changes (hot-reload via the Admin UI).
     *
     * @param chatId   The chat / session identifier.
     * @param commands Enabled commands to surface.  An empty list clears the menu.
     */
    default void registerCommands(String chatId, List<CommandEntry> commands) {}

    boolean isConnected();

    void stop();
}
