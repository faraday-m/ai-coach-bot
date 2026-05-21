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
     * Delete all but the most recent {@code keepCount} messages for the pair.
     * Called after trimming the in-memory list to keep DB and memory in sync.
     */
    void trim(String agentId, String userId, int keepCount);
}
