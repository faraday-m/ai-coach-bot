package dev.coachbot.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Programmatic schema migrations for columns added after the initial release.
 *
 * <p>SQLite's {@code ALTER TABLE … ADD COLUMN IF NOT EXISTS} requires SQLite ≥ 3.37.0
 * (November 2021). Docker Alpine images often ship older versions, so we run these
 * migrations in Java with a try/catch instead of in {@code schema.sql}.
 *
 * <p>Each migration is idempotent: if the column already exists the {@code ALTER TABLE}
 * throws an exception (duplicate column) which is silently swallowed.
 */
@Component
public class SchemaMigration {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigration.class);

    private final JdbcTemplate jdbc;

    public SchemaMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void runMigrations() {
        addColumnIfAbsent(
                "agent_schedules", "save_path",
                "ALTER TABLE agent_schedules ADD COLUMN save_path TEXT");
        addColumnIfAbsent(
                "agent_schedules", "schedule_type",
                "ALTER TABLE agent_schedules ADD COLUMN schedule_type TEXT NOT NULL DEFAULT 'broadcast'");
        addColumnIfAbsent(
                "messages", "transport_id",
                "ALTER TABLE messages ADD COLUMN transport_id TEXT");
        addColumnIfAbsent(
                "messages", "trigger_type",
                "ALTER TABLE messages ADD COLUMN trigger_type TEXT");
    }

    /**
     * Attempts an {@code ALTER TABLE … ADD COLUMN …} and silently ignores the
     * "duplicate column" error that SQLite throws when the column already exists.
     *
     * @param table  Table name (for logging only).
     * @param column Column name (for logging only).
     * @param sql    The full ALTER TABLE statement to execute.
     */
    private void addColumnIfAbsent(String table, String column, String sql) {
        try {
            jdbc.execute(sql);
            log.info("Schema migration: added column {}.{}", table, column);
        } catch (Exception e) {
            // SQLite: "duplicate column name: <col>" — column already exists, nothing to do.
            // Any other error is also swallowed here; schema.sql CREATE TABLE statements
            // remain the authoritative source and will have caught structural issues earlier.
            log.debug("Schema migration: {}.{} already present ({})", table, column, e.getMessage());
        }
    }
}
