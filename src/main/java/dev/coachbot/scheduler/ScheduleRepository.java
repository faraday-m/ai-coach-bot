package dev.coachbot.scheduler;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * CRUD for the {@code agent_schedules} table.
 */
@Repository
public class ScheduleRepository {

    private final JdbcTemplate jdbc;

    public ScheduleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns all enabled schedules. Called once at startup to register cron tasks. */
    public List<AgentSchedule> findAllEnabled() {
        return jdbc.query(
                "SELECT id, agent_id, cron, prompt, save_path, schedule_type FROM agent_schedules WHERE enabled = 1",
                (rs, __) -> new AgentSchedule(
                        rs.getLong("id"),
                        rs.getString("agent_id"),
                        rs.getString("cron"),
                        rs.getString("prompt"),
                        true,
                        rs.getString("save_path"),
                        coalesce(rs.getString("schedule_type"), "broadcast")
                ));
    }

    /** Returns all schedules for a specific agent (including disabled ones). */
    public List<AgentSchedule> findByAgent(String agentId) {
        return jdbc.query(
                "SELECT id, agent_id, cron, prompt, enabled, save_path, schedule_type FROM agent_schedules WHERE agent_id = ?",
                (rs, __) -> new AgentSchedule(
                        rs.getLong("id"),
                        rs.getString("agent_id"),
                        rs.getString("cron"),
                        rs.getString("prompt"),
                        rs.getInt("enabled") == 1,
                        rs.getString("save_path"),
                        coalesce(rs.getString("schedule_type"), "broadcast")
                ),
                agentId);
    }

    /** Inserts a new broadcast schedule. Returns the generated ID. */
    public long insert(String agentId, String cron, String prompt, String savePath) {
        return insert(agentId, cron, prompt, savePath, "broadcast");
    }

    /** Inserts a new schedule with an explicit type. Returns the generated ID. */
    public long insert(String agentId, String cron, String prompt, String savePath, String scheduleType) {
        jdbc.update(
                "INSERT INTO agent_schedules (agent_id, cron, prompt, save_path, schedule_type, enabled, created_at) VALUES (?,?,?,?,?,1,?)",
                agentId, cron, prompt, nullIfBlank(savePath),
                scheduleType == null ? "broadcast" : scheduleType,
                Instant.now().toString());
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    /** Updates cron/prompt/savePath, preserving the existing {@code schedule_type}. */
    public void update(long id, String cron, String prompt, String savePath) {
        jdbc.update("UPDATE agent_schedules SET cron = ?, prompt = ?, save_path = ? WHERE id = ?",
                cron, prompt, nullIfBlank(savePath), id);
    }

    /** Updates all editable fields including {@code schedule_type}. */
    public void update(long id, String cron, String prompt, String savePath, String scheduleType) {
        jdbc.update(
                "UPDATE agent_schedules SET cron = ?, prompt = ?, save_path = ?, schedule_type = ? WHERE id = ?",
                cron, prompt, nullIfBlank(savePath),
                scheduleType == null ? "broadcast" : scheduleType,
                id);
    }

    public void setEnabled(long id, boolean enabled) {
        jdbc.update("UPDATE agent_schedules SET enabled = ? WHERE id = ?", enabled ? 1 : 0, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM agent_schedules WHERE id = ?", id);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String coalesce(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    // ── Record ─────────────────────────────────────────────────────────────────

    /**
     * @param savePath     optional storage path; supports {@code {date}} placeholder.
     *                     {@code null} means "don't save to storage".
     * @param scheduleType {@code "broadcast"} (default) or {@code "spaced_review"}.
     */
    public record AgentSchedule(long id, String agentId, String cron, String prompt, boolean enabled,
                                String savePath, String scheduleType) {}
}
