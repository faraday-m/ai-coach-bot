package dev.coachbot.core;

import dev.coachbot.config.SeedProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * CRUD for agents and their transport mappings.
 *
 * <p>On first startup (empty {@code agents} table) seeds from {@link SeedProperties}.
 * After seeding, SQLite is the source of truth — YAML config is no longer consulted.
 */
@Repository
public class AgentRepository {

    private static final Logger log = LoggerFactory.getLogger(AgentRepository.class);

    private final JdbcTemplate jdbc;
    private final SeedProperties seedProperties;

    public AgentRepository(JdbcTemplate jdbc, SeedProperties seedProperties) {
        this.jdbc = jdbc;
        this.seedProperties = seedProperties;
    }

    @PostConstruct
    public void seedIfEmpty() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM agents", Integer.class);
        if (count != null && count > 0) {
            log.info("Agents already seeded ({} found), skipping YAML seed", count);
            return;
        }

        log.info("No agents found — seeding from application.yml");
        for (SeedProperties.AgentSeed seed : seedProperties.getAgents()) {
            String systemPrompt = loadPrompt(seed);
            insertAgent(new AgentConfig(
                    seed.getId(), seed.getName(), systemPrompt,
                    seed.getLlmBackend(), seed.getStorageBackend(),
                    seed.getTrigger(), seed.isRequireTrigger(), true
            ));
            for (SeedProperties.TransportSeed ts : seed.getTransports()) {
                insertTransport(seed.getId(), ts.getTransportId(), ts.getChatId());
            }
            log.info("Seeded agent '{}' on transports: {}",
                    seed.getId(), seed.getTransports().stream()
                            .map(t -> t.getTransportId() + "/" + t.getChatId()).toList());
        }
    }

    private String loadPrompt(SeedProperties.AgentSeed seed) {
        if (seed.getSystemPromptClasspath() != null) {
            try {
                var resource = new ClassPathResource(seed.getSystemPromptClasspath());
                return resource.getContentAsString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("Could not load classpath prompt '{}', using empty string: {}",
                        seed.getSystemPromptClasspath(), e.getMessage());
            }
        }
        return seed.getSystemPrompt() != null ? seed.getSystemPrompt() : "";
    }

    // ── Queries ────────────────────────────────────────────────────────────────

    /** All agents, including disabled ones. Used by the management CLI. */
    public List<AgentConfig> findAll() {
        return jdbc.query("SELECT * FROM agents ORDER BY id",
                (rs, __) -> mapAgent(rs));
    }

    public java.util.Optional<AgentConfig> findById(String id) {
        List<AgentConfig> rows = jdbc.query(
                "SELECT * FROM agents WHERE id = ?", (rs, __) -> mapAgent(rs), id);
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
    }

    /** Returns all (transportId, chatId) pairs for a given agent. */
    public List<TransportBinding> findTransports(String agentId) {
        return jdbc.query(
                "SELECT transport_id, chat_id FROM agent_transports WHERE agent_id = ? ORDER BY transport_id, chat_id",
                (rs, __) -> new TransportBinding(rs.getString("transport_id"), rs.getString("chat_id")),
                agentId);
    }

    public record TransportBinding(String transportId, String chatId) {}

    public List<AgentConfig> findAllEnabled() {
        return jdbc.query(
                "SELECT * FROM agents WHERE enabled = 1",
                (rs, __) -> new AgentConfig(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("system_prompt"),
                        rs.getString("llm_backend"),
                        rs.getString("storage_backend"),
                        rs.getString("trigger"),
                        rs.getInt("require_trigger") == 1,
                        rs.getInt("enabled") == 1
                ));
    }

    /**
     * Returns agents that are subscribed to the given (transportId, chatId) pair.
     * Used by the Orchestrator to route incoming messages.
     */
    public List<AgentConfig> findByTransport(String transportId, String chatId) {
        return jdbc.query(
                """
                SELECT a.* FROM agents a
                JOIN agent_transports at ON a.id = at.agent_id
                WHERE at.transport_id = ? AND at.chat_id = ? AND a.enabled = 1
                """,
                (rs, __) -> new AgentConfig(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("system_prompt"),
                        rs.getString("llm_backend"),
                        rs.getString("storage_backend"),
                        rs.getString("trigger"),
                        rs.getInt("require_trigger") == 1,
                        rs.getInt("enabled") == 1
                ),
                transportId, chatId
        );
    }

    // ── Mutations ──────────────────────────────────────────────────────────────

    public void insertAgent(AgentConfig agent) {
        String now = Instant.now().toString();
        jdbc.update(
                """
                INSERT INTO agents
                    (id, name, system_prompt, llm_backend, storage_backend,
                     trigger, require_trigger, enabled, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                agent.id(), agent.name(), agent.systemPrompt(),
                agent.llmBackendId(), agent.storageBackendId(),
                agent.trigger(), agent.requireTrigger() ? 1 : 0,
                agent.enabled() ? 1 : 0, now, now
        );
    }

    public void updateSystemPrompt(String agentId, String newPrompt) {
        jdbc.update(
                "UPDATE agents SET system_prompt = ?, updated_at = ? WHERE id = ?",
                newPrompt, Instant.now().toString(), agentId
        );
    }

    public void updateLlmBackend(String agentId, String newBackend) {
        jdbc.update("UPDATE agents SET llm_backend = ?, updated_at = ? WHERE id = ?",
                newBackend, Instant.now().toString(), agentId);
    }

    public void setEnabled(String agentId, boolean enabled) {
        jdbc.update("UPDATE agents SET enabled = ?, updated_at = ? WHERE id = ?",
                enabled ? 1 : 0, Instant.now().toString(), agentId);
    }

    public void updateAgent(AgentConfig agent) {
        jdbc.update("""
                UPDATE agents SET name=?, llm_backend=?, storage_backend=?,
                    trigger=?, require_trigger=?, updated_at=?
                WHERE id=?
                """,
                agent.name(), agent.llmBackendId(), agent.storageBackendId(),
                agent.trigger(), agent.requireTrigger() ? 1 : 0,
                Instant.now().toString(), agent.id());
    }

    public void deleteTransport(String agentId, String transportId, String chatId) {
        jdbc.update("DELETE FROM agent_transports WHERE agent_id=? AND transport_id=? AND chat_id=?",
                agentId, transportId, chatId);
    }

    public void insertTransport(String agentId, String transportId, String chatId) {
        jdbc.update(
                "INSERT OR IGNORE INTO agent_transports (agent_id, transport_id, chat_id) VALUES (?,?,?)",
                agentId, transportId, chatId);
    }

    // ── Shared row mapper ──────────────────────────────────────────────────────

    private static AgentConfig mapAgent(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AgentConfig(
                rs.getString("id"), rs.getString("name"), rs.getString("system_prompt"),
                rs.getString("llm_backend"), rs.getString("storage_backend"),
                rs.getString("trigger"), rs.getInt("require_trigger") == 1,
                rs.getInt("enabled") == 1);
    }
}
