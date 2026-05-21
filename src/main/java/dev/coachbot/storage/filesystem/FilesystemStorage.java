package dev.coachbot.storage.filesystem;

import dev.coachbot.storage.StorageBackend;
import dev.coachbot.storage.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Storage backend that writes plain Markdown files to a local directory.
 * Enabled when {@code bot.storage.filesystem.enabled=true} (default: true).
 */
@Component
@ConditionalOnProperty(prefix = "bot.storage.filesystem", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class FilesystemStorage implements StorageBackend {

    private static final Logger log = LoggerFactory.getLogger(FilesystemStorage.class);

    private final Path basePath;

    public FilesystemStorage(@Value("${bot.storage.filesystem.base-path:notes}") String basePath) {
        this.basePath = Path.of(basePath).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(basePath);
        log.info("Filesystem storage ready at: {}", basePath);
    }

    @Override
    public String id() {
        return "filesystem";
    }

    @Override
    public void write(String path, String content) throws StorageException {
        try {
            Path target = resolve(path);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new StorageException("write failed: " + path, e);
        }
    }

    @Override
    public void append(String path, String content) throws StorageException {
        try {
            Path target = resolve(path);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new StorageException("append failed: " + path, e);
        }
    }

    @Override
    public Optional<String> read(String path) throws StorageException {
        try {
            Path target = resolve(path);
            if (!Files.exists(target)) return Optional.empty();
            return Optional.of(Files.readString(target));
        } catch (IOException e) {
            throw new StorageException("read failed: " + path, e);
        }
    }

    @Override
    public List<String> list(String prefix) throws StorageException {
        try {
            Path dir = resolve(prefix);
            if (!Files.isDirectory(dir)) return List.of();
            try (var stream = Files.list(dir)) {
                return stream
                        .map(p -> basePath.relativize(p).toString())
                        .collect(Collectors.toList());
            }
        } catch (IOException e) {
            throw new StorageException("list failed: " + prefix, e);
        }
    }

    @Override
    public void delete(String path) throws StorageException {
        try {
            Files.deleteIfExists(resolve(path));
        } catch (IOException e) {
            throw new StorageException("delete failed: " + path, e);
        }
    }

    /** Resolves {@code path} under {@code basePath} and guards against path traversal. */
    private Path resolve(String path) {
        Path resolved = basePath.resolve(path).normalize();
        if (!resolved.startsWith(basePath)) {
            throw new IllegalArgumentException("Path traversal detected: " + path);
        }
        return resolved;
    }
}
