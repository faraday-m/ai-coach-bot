package dev.coachbot.core;

/**
 * Runtime representation of an agent loaded from the {@code agents} SQLite table.
 * Immutable — changes to an agent require reloading the GroupSession.
 */
public record AgentConfig(
        String id,
        String name,
        String systemPrompt,
        String llmBackendId,
        String storageBackendId,
        String trigger,
        boolean requireTrigger,
        boolean enabled
) {}
