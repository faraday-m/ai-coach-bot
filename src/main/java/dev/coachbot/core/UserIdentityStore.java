package dev.coachbot.core;

/**
 * Maps transport-specific sender identifiers to a stable canonical user ID.
 *
 * <p>Each user can write from multiple transports (Telegram, XMPP, Console).
 * The canonical ID ties all those identities together so they share one
 * conversation history with each agent.
 *
 * <p>Transport key format: {@code "{transportId}:{senderId}"} —
 * e.g. {@code "telegram:123456789"}, {@code "console:console"}.
 *
 * <p>Canonical ID format: {@code "user:{12-hex-chars}"} — stable, opaque UUID prefix.
 *
 * <p>The interface is {@code @FunctionalInterface}-compatible (single abstract method)
 * so tests can supply a simple lambda: {@code (transportId, senderId) -> senderId}.
 */
public interface UserIdentityStore {

    /**
     * Returns the canonical ID for the given sender.
     * Creates a new canonical ID automatically on first encounter.
     */
    String resolve(String transportId, String senderId);

    /**
     * Returns all transport keys registered to the given canonical user ID.
     *
     * <p>Default implementation returns an empty list — only {@link UserIdentityRepository}
     * provides a real DB-backed result. Tests that don't care about transport-key lookup
     * can leave this as-is.
     */
    default java.util.List<String> findTransportKeysByCanonicalId(String canonicalId) {
        return java.util.List.of();
    }

    /**
     * Links {@code fromTransportKey} to an existing canonical ID.
     *
     * <p>All transport keys previously associated with {@code fromTransportKey}'s
     * canonical ID are reassigned to {@code toCanonicalId}, effectively merging
     * two identities into one. Used by the {@code /link} command.
     *
     * <p>Default implementation throws — only {@link UserIdentityRepository} supports this.
     */
    default void link(String fromTransportKey, String toCanonicalId) {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " does not support identity linking");
    }
}
