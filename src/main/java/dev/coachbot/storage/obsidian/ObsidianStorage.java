package dev.coachbot.storage.obsidian;

import dev.coachbot.storage.StorageBackend;
import dev.coachbot.storage.StorageException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Storage backend that writes Obsidian-flavoured Markdown files into an Obsidian vault.
 *
 * <p>Each note is created with a YAML frontmatter block:
 * <pre>
 * ---
 * created: 2025-05-21
 * updated: 2025-05-21 14:30
 * tags: [coach-bot]
 * ---
 *
 * &lt;content&gt;
 * </pre>
 *
 * <p>Files are stored at {@code <vault-path>/<path>.md}.
 * The {@code path} argument must not contain {@code ..} — path-traversal is rejected.
 *
 * <p>Enabled when {@code bot.storage.obsidian.enabled=true} and
 * {@code bot.storage.obsidian.vault-path} points to the vault root.
 */
@Component
@ConditionalOnProperty(prefix = "bot.storage.obsidian", name = "enabled", havingValue = "true")
public class ObsidianStorage implements StorageBackend {

    private static final Logger log = LoggerFactory.getLogger(ObsidianStorage.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String FRONTMATTER_SEPARATOR = "---";

    private final Path vaultPath;
    /** Optional sub-folder inside the vault where bot notes are stored. */
    private final String notesSubfolder;

    public ObsidianStorage(
            @Value("${bot.storage.obsidian.vault-path}") String vaultPath,
            @Value("${bot.storage.obsidian.notes-subfolder:coach-bot}") String notesSubfolder) {
        this.vaultPath      = Path.of(vaultPath).toAbsolutePath().normalize();
        this.notesSubfolder = notesSubfolder;
    }

    @PostConstruct
    public void init() throws IOException {
        if (!Files.isDirectory(vaultPath)) {
            throw new IllegalStateException(
                    "Obsidian vault not found at: " + vaultPath +
                    " — set bot.storage.obsidian.vault-path or BOT_STORAGE_OBSIDIAN_VAULT_PATH");
        }
        Path notesDir = vaultPath.resolve(notesSubfolder);
        Files.createDirectories(notesDir);
        log.info("Obsidian storage ready: vault={} subfolder={}", vaultPath, notesSubfolder);
    }

    @Override
    public String id() {
        return "obsidian";
    }

    // ── Write / overwrite ──────────────────────────────────────────────────────

    /**
     * Creates or overwrites a note. The content is wrapped in YAML frontmatter.
     * If the content already starts with a frontmatter block ({@code ---}) it is
     * written as-is (assumed to be pre-formatted).
     */
    @Override
    public void write(String path, String content) throws StorageException {
        Path target = resolveNotePath(path);
        try {
            Files.createDirectories(target.getParent());
            String fullContent = content.startsWith(FRONTMATTER_SEPARATOR)
                    ? content
                    : buildNote(content, /* isNew= */ !Files.exists(target));
            Files.writeString(target, fullContent,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.debug("Obsidian write: {}", vaultPath.relativize(target));
        } catch (IOException e) {
            throw new StorageException("Obsidian write failed: " + path, e);
        }
    }

    /**
     * Appends {@code content} to an existing note, or creates it if it doesn't exist.
     * A horizontal rule separator and timestamp are added before the new content.
     */
    @Override
    public void append(String path, String content) throws StorageException {
        Path target = resolveNotePath(path);
        try {
            Files.createDirectories(target.getParent());
            if (!Files.exists(target)) {
                // New file — write with frontmatter
                write(path, content);
                return;
            }
            // Append to existing note with a divider + timestamp
            String divider = "\n\n---\n*" + LocalDateTime.now().format(DATETIME_FMT) + "*\n\n";
            Files.writeString(target, divider + content, StandardOpenOption.APPEND);

            // Update the `updated` field in frontmatter
            updateFrontmatterDate(target);
            log.debug("Obsidian append: {}", vaultPath.relativize(target));
        } catch (IOException e) {
            throw new StorageException("Obsidian append failed: " + path, e);
        }
    }

    // ── Read ───────────────────────────────────────────────────────────────────

    /**
     * Reads the note content. Frontmatter is stripped — only the body is returned.
     * Returns {@link Optional#empty()} if the note does not exist.
     */
    @Override
    public Optional<String> read(String path) throws StorageException {
        Path target = resolveNotePath(path);
        try {
            if (!Files.exists(target)) return Optional.empty();
            String raw = Files.readString(target);
            return Optional.of(stripFrontmatter(raw));
        } catch (IOException e) {
            throw new StorageException("Obsidian read failed: " + path, e);
        }
    }

    // ── List ───────────────────────────────────────────────────────────────────

    /**
     * Lists all {@code .md} files under the given prefix (relative to the notes subfolder).
     * Returns paths relative to the vault root.
     */
    @Override
    public List<String> list(String prefix) throws StorageException {
        Path dir = vaultPath.resolve(notesSubfolder).resolve(sanitisePath(prefix));
        try {
            if (!Files.isDirectory(dir)) return List.of();
            try (var stream = Files.walk(dir)) {
                return stream
                        .filter(p -> !Files.isDirectory(p))
                        .filter(p -> p.toString().endsWith(".md"))
                        .map(p -> vaultPath.relativize(p).toString())
                        .sorted()
                        .collect(Collectors.toList());
            }
        } catch (IOException e) {
            throw new StorageException("Obsidian list failed: " + prefix, e);
        }
    }

    // ── Delete ─────────────────────────────────────────────────────────────────

    @Override
    public void delete(String path) throws StorageException {
        try {
            Files.deleteIfExists(resolveNotePath(path));
        } catch (IOException e) {
            throw new StorageException("Obsidian delete failed: " + path, e);
        }
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    /**
     * Resolves {@code path} to a {@code .md} file inside the notes subfolder of the vault.
     * Rejects path traversal attempts.
     */
    private Path resolveNotePath(String path) {
        String safe = sanitisePath(path);
        // Ensure .md extension
        if (!safe.endsWith(".md")) safe = safe + ".md";
        Path resolved = vaultPath.resolve(notesSubfolder).resolve(safe).normalize();
        if (!resolved.startsWith(vaultPath)) {
            throw new IllegalArgumentException("Path traversal detected: " + path);
        }
        return resolved;
    }

    private static String sanitisePath(String path) {
        // Reject obvious traversal
        if (path.contains("..")) throw new IllegalArgumentException("Path traversal detected: " + path);
        return path;
    }

    /** Builds a note string with YAML frontmatter. */
    private String buildNote(String body, boolean isNew) {
        String now = LocalDateTime.now().format(DATETIME_FMT);
        String today = LocalDate.now().format(DATE_FMT);
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        if (isNew) sb.append("created: ").append(today).append('\n');
        sb.append("updated: ").append(now).append('\n');
        sb.append("tags: [coach-bot]\n");
        sb.append("---\n\n");
        sb.append(body);
        return sb.toString();
    }

    /**
     * Strips the YAML frontmatter block from note content.
     * Returns the body starting after the closing {@code ---}.
     */
    public static String stripFrontmatter(String content) {
        if (!content.startsWith("---")) return content;
        int end = content.indexOf("\n---", 3);
        if (end == -1) return content; // malformed — return as-is
        // Skip past the closing --- and any following blank lines
        int bodyStart = end + 4; // skip "\n---"
        while (bodyStart < content.length() && content.charAt(bodyStart) == '\n') bodyStart++;
        return content.substring(bodyStart);
    }

    /** Updates the {@code updated:} field in the frontmatter of an existing note. */
    private void updateFrontmatterDate(Path file) throws IOException {
        String raw = Files.readString(file);
        if (!raw.startsWith("---")) return;
        String updated = LocalDateTime.now().format(DATETIME_FMT);
        String newRaw = raw.replaceFirst("(?m)^updated:.*$", "updated: " + updated);
        Files.writeString(file, newRaw, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
