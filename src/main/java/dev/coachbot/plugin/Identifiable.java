package dev.coachbot.plugin;

/**
 * Marker interface for all pluggable components (transports, LLM backends, storage backends).
 * The {@code id()} is referenced by name in {@code application.yml} and in the agents table.
 */
public interface Identifiable {
    /** Unique stable identifier, e.g. "claude", "telegram", "filesystem". */
    String id();
}
