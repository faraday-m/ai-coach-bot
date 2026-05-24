package dev.coachbot.memory;

import dev.coachbot.llm.LlmBackend;
import dev.coachbot.llm.LlmRequest;
import dev.coachbot.storage.StorageBackend;
import dev.coachbot.storage.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Maintains a per-user learning-memory document in the agent's storage backend.
 *
 * <p>The memory document lives at {@code coach-bot/memory/{agentId}/{safeUserId}.md}
 * and is visible/editable directly in Obsidian or the filesystem.
 *
 * <p>This is a plain class (not a Spring bean) — it is instantiated by {@link dev.coachbot.core.GroupSession}
 * which already holds the {@link StorageBackend} and {@link LlmBackend} references needed here.
 * This mirrors the pattern used by {@code callWikiAgent()} in GroupSession.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>After each {@code /wiki} save, {@link #updateAsync} is called fire-and-forget.</li>
 *   <li>At every LLM request, {@link #load} injects the memory into the system prompt.</li>
 *   <li>Users can view/edit via {@code /memory}, {@code /memory add}, {@code /memory reset}.</li>
 * </ol>
 */
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    /** Path template: agentId and safeUserId are substituted at runtime. */
    static final String MEMORY_PATH_TEMPLATE = "coach-bot/memory/%s/%s.md";

    private final StorageBackend storage;
    private final LlmBackend llm;
    /** Loaded once from classpath: prompts/meta/memory-update.md */
    private final String promptTemplate;
    /**
     * Loaded once from classpath: prompts/meta/memory-bootstrap.md
     * May be {@code null} if the classpath resource is unavailable — bootstrap is then skipped.
     */
    private final String bootstrapPromptTemplate;

    /**
     * Full constructor — used in production.
     *
     * @param bootstrapPromptTemplate may be {@code null}; bootstrap is silently skipped when null
     */
    public MemoryService(StorageBackend storage, LlmBackend llm,
                         String promptTemplate, String bootstrapPromptTemplate) {
        this.storage                 = storage;
        this.llm                     = llm;
        this.promptTemplate          = promptTemplate;
        this.bootstrapPromptTemplate = bootstrapPromptTemplate;
    }

    /**
     * Backwards-compatible 3-arg constructor (no bootstrap support).
     * Used by tests that don't need bootstrap behaviour.
     */
    public MemoryService(StorageBackend storage, LlmBackend llm, String promptTemplate) {
        this(storage, llm, promptTemplate, null);
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Returns the current memory document for this user, or {@code empty()} if none exists yet.
     * Called synchronously at request time — fast (filesystem read).
     */
    public Optional<String> load(String agentId, String canonUserId) {
        try {
            return storage.read(memoryPath(agentId, canonUserId));
        } catch (StorageException e) {
            log.warn("[memory] Failed to load memory for {}/{}: {}", agentId, canonUserId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Fires a background LLM call that merges {@code newWikiContent} into the current memory
     * document and saves the result.  Errors are logged but never propagated — the caller
     * (GroupSession) must not be blocked or affected by memory update failures.
     */
    public void updateAsync(String agentId, String canonUserId, String newWikiContent) {
        Thread.ofVirtual()
              .name("memory-update-" + agentId + "-" + safeFilename(canonUserId))
              .start(() -> {
                  try {
                      doUpdate(agentId, canonUserId, newWikiContent);
                  } catch (Exception e) {
                      log.warn("[memory] Async update failed for {}/{}", agentId, canonUserId, e);
                  }
              });
    }

    /**
     * Fires a background LLM call that generates an <em>initial</em> memory document for a
     * user who has no memory yet.
     *
     * <p>The bootstrap prompt asks the LLM to derive key learning topics from the agent's
     * system prompt and its slash-command list.  The result is written to storage so that
     * the next message the user sends already has memory injected into the system prompt.
     *
     * <p>This method is a no-op (logs a debug line) when:
     * <ul>
     *   <li>the bootstrap prompt template was not provided at construction time, or</li>
     *   <li>a memory file already exists and is non-blank (race-condition guard).</li>
     * </ul>
     *
     * @param agentSystemPrompt the agent's master system prompt — describes the coaching domain
     * @param commandsText      pre-formatted command list (e.g. {@code "/quiz — Start a quiz\n/hint — Get a hint"});
     *                          pass an empty string if the agent has no commands
     */
    /**
     * Tracks (agentId + "/" + safeFilename) pairs where bootstrap has already been triggered
     * in this session — ensures we fire at most once per user per JVM lifetime, even if
     * {@code bootstrapAsync()} is called from the message path on every message while storage
     * hasn't written yet.
     */
    private final java.util.Set<String> bootstrapTriggered =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public void bootstrapAsync(String agentId, String canonUserId,
                               String agentSystemPrompt, String commandsText) {
        if (bootstrapPromptTemplate == null || bootstrapPromptTemplate.isBlank()) {
            log.debug("[memory] Bootstrap skipped for {}/{} — no bootstrap prompt available", agentId, canonUserId);
            return;
        }
        String key = agentId + "/" + safeFilename(canonUserId);
        if (!bootstrapTriggered.add(key)) {
            log.debug("[memory] Bootstrap already triggered for {}/{} — skipping duplicate", agentId, canonUserId);
            return;
        }
        Thread.ofVirtual()
              .name("memory-bootstrap-" + agentId + "-" + safeFilename(canonUserId))
              .start(() -> {
                  try {
                      doBootstrap(agentId, canonUserId, agentSystemPrompt, commandsText);
                  } catch (Exception e) {
                      log.warn("[memory] Async bootstrap failed for {}/{}", agentId, canonUserId, e);
                  } finally {
                      // Remove so the next message can retrigger if memory was reset/deleted
                      bootstrapTriggered.remove(key);
                  }
              });
    }

    private void doBootstrap(String agentId, String canonUserId,
                             String agentSystemPrompt, String commandsText) throws StorageException {
        String path = memoryPath(agentId, canonUserId);
        // Re-check under the virtual thread — another message may have triggered bootstrap
        // concurrently and already written the file.
        Optional<String> existing = storage.read(path);
        if (existing.isPresent() && !existing.get().isBlank()) {
            log.debug("[memory] Bootstrap skipped for {}/{} — memory already present", agentId, canonUserId);
            return;
        }

        log.info("[memory] No memory for {}/{} — bootstrapping initial topics (LLM call in progress…)",
                agentId, canonUserId);

        StringBuilder input = new StringBuilder("Agent system prompt:\n").append(agentSystemPrompt);
        if (commandsText != null && !commandsText.isBlank()) {
            input.append("\n\nAvailable commands:\n").append(commandsText);
        }

        LlmRequest request = LlmRequest.of(
                bootstrapPromptTemplate, List.of(), input.toString(), "bootstrap", agentId);
        String content = llm.complete(request).text();

        if (content == null || content.isBlank()) {
            log.warn("[memory] Bootstrap LLM returned empty content for {}/{} — skipping", agentId, canonUserId);
            return;
        }

        storage.write(path, content);
        log.info("[memory] Bootstrapped initial memory for {}/{} ({} chars)", agentId, canonUserId, content.length());
    }

    /**
     * Appends a timestamped entry to the {@code ## Coach Notes} section directly
     * (no LLM call — used by {@code /memory add}).
     */
    public void appendNote(String agentId, String canonUserId, String note) {
        String path = memoryPath(agentId, canonUserId);
        String entry = "\n- " + java.time.LocalDate.now() + ": " + note;
        try {
            // Ensure the file exists first; if not, create a minimal stub
            Optional<String> current = storage.read(path);
            if (current.isEmpty()) {
                storage.write(path, "## Coach Notes\n" + entry + "\n");
            } else {
                storage.append(path, entry + "\n");
            }
        } catch (StorageException e) {
            log.warn("[memory] Failed to append note for {}/{}: {}", agentId, canonUserId, e.getMessage());
            throw new RuntimeException("Could not append to memory: " + e.getMessage(), e);
        }
    }

    /**
     * Deletes the memory document (used by {@code /memory reset}).
     * No-op if the file does not exist.
     */
    public void reset(String agentId, String canonUserId) {
        try {
            storage.delete(memoryPath(agentId, canonUserId));
        } catch (UnsupportedOperationException e) {
            log.warn("[memory] Storage backend does not support delete — memory reset skipped");
            throw e;
        } catch (StorageException e) {
            log.warn("[memory] Failed to reset memory for {}/{}: {}", agentId, canonUserId, e.getMessage());
            throw new RuntimeException("Could not reset memory: " + e.getMessage(), e);
        }
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private void doUpdate(String agentId, String canonUserId, String newWikiContent) throws StorageException {
        String path = memoryPath(agentId, canonUserId);
        String current = storage.read(path).orElse("");

        String userMessage = "Current memory:\n\n" + current
                + "\n\n---\n\nNew wiki note to integrate:\n\n" + newWikiContent;

        LlmRequest request = LlmRequest.of(
                promptTemplate, List.of(), userMessage, "memory", agentId);

        String updated = llm.complete(request).text();
        if (updated == null || updated.isBlank()) {
            log.warn("[memory] LLM returned empty update for {}/{} — skipping write", agentId, canonUserId);
            return;
        }

        storage.write(path, updated);
        log.info("[memory] Updated memory for {}/{} ({} chars)", agentId, canonUserId, updated.length());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    String memoryPath(String agentId, String canonUserId) {
        return MEMORY_PATH_TEMPLATE.formatted(agentId, safeFilename(canonUserId));
    }

    /** Converts a canonical user ID (e.g. "tg:123456", "user:abc") to a safe filename. */
    static String safeFilename(String canonUserId) {
        return canonUserId.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
