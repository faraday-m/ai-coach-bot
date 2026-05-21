package dev.coachbot.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SQLite-backed {@link UserIdentityStore}.
 *
 * <p>Uses {@code INSERT OR IGNORE} + {@code SELECT} so the first call for a new
 * transport key atomically creates a canonical ID without a race window.
 * With SQLite's single-writer model this is safe.
 *
 * <p>{@link #link} merges two canonical IDs by repointing all transport keys
 * of the "from" identity to the "to" identity — the losing canonical ID
 * simply ceases to be referenced.
 */
@Repository
public class UserIdentityRepository implements UserIdentityStore {

    private static final Logger log = LoggerFactory.getLogger(UserIdentityRepository.class);

    private final JdbcTemplate jdbc;

    public UserIdentityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the canonical ID for {@code (transportId, senderId)}, creating one
     * on first encounter.
     *
     * <p>Strategy: generate a fresh candidate ID, attempt {@code INSERT OR IGNORE},
     * then always {@code SELECT} — returns the existing ID if the insert was skipped.
     */
    @Override
    public String resolve(String transportId, String senderId) {
        String transportKey = transportId + ":" + senderId;
        String candidate    = "user:" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String now          = Instant.now().toString();

        jdbc.update(
                "INSERT OR IGNORE INTO user_identities (transport_key, canonical_id, created_at) VALUES (?,?,?)",
                transportKey, candidate, now);

        String canonicalId = jdbc.queryForObject(
                "SELECT canonical_id FROM user_identities WHERE transport_key = ?",
                String.class, transportKey);

        if (canonicalId.equals(candidate)) {
            log.debug("New identity: {} → {}", transportKey, canonicalId);
        }
        return canonicalId;
    }

    /**
     * Links {@code fromTransportKey} to {@code toCanonicalId}.
     *
     * <p>Finds the canonical ID currently assigned to {@code fromTransportKey},
     * then reassigns every transport key with that ID to {@code toCanonicalId}.
     * After this call, the old canonical ID is orphaned.
     */
    @Override
    public void link(String fromTransportKey, String toCanonicalId) {
        List<String> rows = jdbc.queryForList(
                "SELECT canonical_id FROM user_identities WHERE transport_key = ?",
                String.class, fromTransportKey);

        if (rows.isEmpty()) {
            log.warn("Cannot link: transport key '{}' is not registered", fromTransportKey);
            return;
        }
        String fromCanonicalId = rows.get(0);
        if (fromCanonicalId.equals(toCanonicalId)) return; // already the same

        int updated = jdbc.update(
                "UPDATE user_identities SET canonical_id = ? WHERE canonical_id = ?",
                toCanonicalId, fromCanonicalId);

        log.info("Linked {} transport key(s) from {} → {}", updated, fromCanonicalId, toCanonicalId);
    }
}
