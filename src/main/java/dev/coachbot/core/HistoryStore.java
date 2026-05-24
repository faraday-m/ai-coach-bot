package dev.coachbot.core;

import dev.coachbot.llm.ConversationMessage;

import java.util.List;

/**
 * Abstraction over conversation history storage.
 *
 * <p>The real implementation ({@link MessageRepository}) persists to SQLite.
 * Tests use an in-memory implementation so they don't need a database.
 */
public interface HistoryStore {

    /**
     * Load the most recent {@code maxMessages} messages for the given
     * (agentId, userId) pair, in chronological order (oldest first).
     */
    List<ConversationMessage> load(String agentId, String userId, int maxMessages);

    /** Append a single message to the history. */
    void append(String agentId, String userId, ConversationMessage message);

    /**
     * Append a message with transport metadata. The default implementation
     * falls back to the 3-arg overload so existing stubs don't break.
     *
     * @param transportId transport that delivered the message (e.g. "telegram"), may be null
     * @param triggerType how the exchange was triggered: "user_message" | "user_command"
     */
    default void append(String agentId, String userId, ConversationMessage message,
                        String transportId, String triggerType) {
        append(agentId, userId, message);
    }

    /**
     * Returns all messages after the given DB row ID (exclusive), oldest-first.
     * Used by {@code handleWiki()} to get only messages since the last export.
     * Default returns an empty list so stubs don't need to implement it.
     */
    default List<ConversationMessage> loadAfter(String agentId, String userId, long afterId) {
        return List.of();
    }

    /**
     * Returns the DB ID of the most recent message for this (agentId, userId) pair,
     * or {@code 0} if no messages exist yet.
     * Used by {@code handleWiki()} to persist the checkpoint across restarts.
     */
    default long lastMessageId(String agentId, String userId) { return 0L; }

    /**
     * Delete all but the most recent {@code keepCount} messages for the pair.
     * Called after trimming the in-memory list to keep DB and memory in sync.
     */
    void trim(String agentId, String userId, int keepCount);
}
