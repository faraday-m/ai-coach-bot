package dev.coachbot.transport;

import dev.coachbot.plugin.Identifiable;

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
     * Starts the transport and registers the inbound message callback.
     * Must be non-blocking — long-running I/O should use a background thread.
     */
    void start(InboundMessageHandler handler);

    /** Sends a text message to the specified chat. */
    void send(String chatId, String text);

    /** Optional: signal typing indicator while the LLM is thinking. */
    default void sendTyping(String chatId) {}

    boolean isConnected();

    void stop();
}
