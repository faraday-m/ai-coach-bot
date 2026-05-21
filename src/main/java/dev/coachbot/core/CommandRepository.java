package dev.coachbot.core;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

/**
 * CRUD for agent slash-commands stored in {@code agent_commands}.
 *
 * <p>Commands are appended to the agent's system prompt so the LLM knows
 * how to react when a user sends e.g. {@code /quiz} or {@code /hint}.
 */
@Repository
public class CommandRepository {

    public record AgentCommand(long id, String agentId, String trigger, String description, boolean enabled) {}

    private final JdbcTemplate jdbc;

    public CommandRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AgentCommand> findByAgent(String agentId) {
        return jdbc.query(
                "SELECT * FROM agent_commands WHERE agent_id = ? ORDER BY id",
                (rs, __) -> new AgentCommand(
                        rs.getLong("id"),
                        rs.getString("agent_id"),
                        rs.getString("trigger"),
                        rs.getString("description"),
                        rs.getInt("enabled") == 1),
                agentId);
    }

    /** Returns only enabled commands — used at runtime to build the system prompt. */
    public List<AgentCommand> findEnabledByAgent(String agentId) {
        return jdbc.query(
                "SELECT * FROM agent_commands WHERE agent_id = ? AND enabled = 1 ORDER BY id",
                (rs, __) -> new AgentCommand(
                        rs.getLong("id"),
                        rs.getString("agent_id"),
                        rs.getString("trigger"),
                        rs.getString("description"),
                        true),
                agentId);
    }

    public long insert(String agentId, String trigger, String description) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO agent_commands (agent_id, trigger, description, enabled, created_at) VALUES (?,?,?,1,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, agentId);
            ps.setString(2, trigger);
            ps.setString(3, description);
            ps.setString(4, Instant.now().toString());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void update(long id, String trigger, String description) {
        jdbc.update("UPDATE agent_commands SET trigger = ?, description = ? WHERE id = ?",
                trigger, description, id);
    }

    public void setEnabled(long id, boolean enabled) {
        jdbc.update("UPDATE agent_commands SET enabled = ? WHERE id = ?", enabled ? 1 : 0, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM agent_commands WHERE id = ?", id);
    }
}
