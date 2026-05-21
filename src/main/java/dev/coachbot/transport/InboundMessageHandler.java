package dev.coachbot.transport;

/**
 * Callback registered with each transport.
 * The {@link dev.coachbot.core.Orchestrator} implements this to route messages.
 */
@FunctionalInterface
public interface InboundMessageHandler {
    void onMessage(InboundMessage message);
}
