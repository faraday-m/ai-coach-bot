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

    @Override
    public void append(String agentId, String userId, ConversationMessage message,
                       String transportId, String triggerType) {
        jdbc.update(
                "INSERT INTO messages (agent_id, user_id, role, content, created_at, transport_id, trigger_type) " +
                "VALUES (?,?,?,?,?,?,?)",
                agentId, userId, message.role().name(), message.content(),
                Instant.now().toString(), transportId, triggerType);
    }

    @Override
    public List<ConversationMessage> loadAfter(String agentId, String userId, long afterId) {
        return jdbc.query(
                "SELECT role, content FROM messages WHERE agent_id = ? AND user_id = ? AND id > ? ORDER BY id ASC",
                (rs, __) -> new ConversationMessage(
                        ConversationMessage.Role.valueOf(rs.getString("role")),
                        rs.getString("content")),
                agentId, userId, afterId);
    }

    @Override
    public long lastMessageId(String agentId, String userId) {
        Long id = jdbc.queryForObject(
                "SELECT MAX(id) FROM messages WHERE agent_id = ? AND user_id = ?",
                Long.class, agentId, userId);
        return id != null ? id : 0L;
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

    // ── Admin queries ──────────────────────────────────────────────────────────

    /**
     * A message row including transport metadata — used by the admin Messages tab.
     */
    public record MessageRow(long id, String userId, String role, String content,
                              String createdAt, String transportId, String triggerType) {}

    /**
     * Returns up to {@code limit} most recent messages for an agent, newest-first.
     * Delegates to the offset-aware overload with {@code offset = 0}.
     */
    public List<MessageRow> findMessages(String agentId, String transportId,
                                          String triggerType, int limit) {
        return findMessages(agentId, transportId, triggerType, 0, limit);
    }

    /**
     * Returns messages for an agent with pagination support, newest-first.
     * Pass {@code null} for {@code transportId} or {@code triggerType} to match all values.
     */
    public List<MessageRow> findMessages(String agentId, String transportId,
                                          String triggerType, int offset, int limit) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT id, user_id, role, content, created_at, transport_id, trigger_type " +
                "FROM messages WHERE agent_id = ?");
        params.add(agentId);
        if (transportId != null) {
            sql.append(" AND transport_id = ?");
            params.add(transportId);
        }
        if (triggerType != null) {
            sql.append(" AND trigger_type = ?");
            params.add(triggerType);
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return jdbc.query(sql.toString(), params.toArray(),
                (rs, __) -> new MessageRow(
                        rs.getLong("id"),
                        rs.getString("user_id"),
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getString("created_at"),
                        rs.getString("transport_id"),
                        rs.getString("trigger_type")));
    }

    /**
     * Returns the total number of messages for an agent matching the given filters.
     * Used by the paginated admin Messages tab to tell the grid the total row count.
     */
    public int countMessages(String agentId, String transportId, String triggerType) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM messages WHERE agent_id = ?");
        params.add(agentId);
        if (transportId != null) {
            sql.append(" AND transport_id = ?");
            params.add(transportId);
        }
        if (triggerType != null) {
            sql.append(" AND trigger_type = ?");
            params.add(triggerType);
        }
        Integer count = jdbc.queryForObject(sql.toString(), Integer.class, params.toArray());
        return count != null ? count : 0;
    }
}
