package dev.coachbot.core;

import dev.coachbot.llm.ConversationMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SQLite-backed conversation history.
 *
 * <p>Messages are stored in the {@code messages} table and loaded lazily per user
 * when their first message arrives in a session. On restart the history is
 * rehydrated from the DB, so conversation context survives JVM restarts.
 *
 * <p>The table grows unboundedly unless {@link #trim} is called — {@link GroupSession}
 * calls it whenever it trims its in-memory list.
 */
@Repository
public class MessageRepository implements HistoryStore {

    private final JdbcTemplate jdbc;

    public MessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Loads the most recent {@code maxMessages} messages in chronological order.
     * Uses DESC + LIMIT to grab the tail efficiently, then reverses in memory.
     */
    @Override
    public List<ConversationMessage> load(String agentId, String userId, int maxMessages) {
        List<ConversationMessage> rows = jdbc.query(
                """
                SELECT role, content FROM messages
                WHERE agent_id = ? AND user_id = ?
                ORDER BY id DESC LIMIT ?
                """,
                (rs, __) -> new ConversationMessage(
                        ConversationMessage.Role.valueOf(rs.getString("role")),
                        rs.getString("content")),
                agentId, userId, maxMessages);

        Collections.reverse(rows);   // DESC → chronological
        return new ArrayList<>(rows);
    }

    @Override
    public void append(String agentId, String userId, ConversationMessage message) {
        jdbc.update(
                "INSERT INTO messages (agent_id, user_id, role, content, created_at) VALUES (?,?,?,?,?)",
                agentId, userId, message.role().name(), message.content(), Instant.now().toString());
    }

    /**
     * Deletes all but the most recent {@code keepCount} messages for the pair.
     * SQLite supports this cleanly with a NOT IN subquery.
     */
    @Override
    public void trim(String agentId, String userId, int keepCount) {
        jdbc.update(
                """
                DELETE FROM messages
                WHERE agent_id = ? AND user_id = ?
                  AND id NOT IN (
                      SELECT id FROM messages
                      WHERE agent_id = ? AND user_id = ?
                      ORDER BY id DESC LIMIT ?
                  )
                """,
                agentId, userId, agentId, userId, keepCount);
    }
}
