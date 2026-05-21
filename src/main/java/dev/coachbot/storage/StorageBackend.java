package dev.coachbot.storage;

import dev.coachbot.plugin.Identifiable;

import java.util.List;
import java.util.Optional;

/**
 * SPI for storage backends (filesystem, Obsidian, Git, REST, Notion, …).
 *
 * <p>The {@code path} parameter uses a virtual path convention; each implementation
 * interprets it according to its backing system:
 * <ul>
 *   <li>{@code FilesystemStorage} — real file path under {@code base-path}</li>
 *   <li>{@code ObsidianStorage}   — path inside the Obsidian vault</li>
 *   <li>{@code GitStorage}        — file path in the repo; {@code write} triggers commit+push</li>
 *   <li>{@code RestStorage}       — URL suffix; {@code write} = POST, {@code read} = GET</li>
 * </ul>
 *
 * <p>To add a new storage backend:
 * <ol>
 *   <li>Implement this interface.</li>
 *   <li>Annotate with {@code @Component} (+ {@code @ConditionalOnProperty} if optional).</li>
 *   <li>Reference by {@link #id()} in agent config.</li>
 * </ol>
 */
public interface StorageBackend extends Identifiable {

    /** Creates or overwrites the content at {@code path}. */
    void write(String path, String content) throws StorageException;

    /** Appends content to the file at {@code path}, creating it if absent. */
    void append(String path, String content) throws StorageException;

    /** Returns the content at {@code path}, or {@code empty()} if it does not exist. */
    Optional<String> read(String path) throws StorageException;

    /** Lists entries under {@code prefix} (non-recursive). */
    List<String> list(String prefix) throws StorageException;

    /** Optional: deletes the entry at {@code path}. */
    default void delete(String path) throws StorageException {
        throw new UnsupportedOperationException(id() + " does not support delete");
    }
}
