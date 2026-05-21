package dev.coachbot.transport;

import java.time.Instant;
import java.util.Objects;

/**
 * Normalized inbound message from any transport.
 * All transport-specific details are stripped; only the semantic content remains.
 */
public record InboundMessage(
        String transportId,   // e.g. "telegram", "console"
        String chatId,        // transport-specific chat identifier, e.g. "tg:-100123"
        String senderId,      // transport-specific user identifier
        String senderName,    // display name
        String text,          // message content (trigger prefix already stripped by Orchestrator)
        Instant timestamp
) {
    public InboundMessage {
        Objects.requireNonNull(transportId, "transportId");
        Objects.requireNonNull(chatId, "chatId");
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(senderName, "senderName");
        Objects.requireNonNull(text, "text");
        if (timestamp == null) timestamp = Instant.now();
    }

    /** Returns a copy with the text replaced (used to strip the trigger prefix). */
    public InboundMessage withText(String newText) {
        return new InboundMessage(transportId, chatId, senderId, senderName, newText, timestamp);
    }
}
