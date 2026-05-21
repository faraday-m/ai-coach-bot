package dev.coachbot.storage;

import dev.coachbot.storage.obsidian.ObsidianStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ObsidianStorage} — uses a temporary directory as the vault.
 */
class ObsidianStorageTest {

    @TempDir
    Path vault;

    private ObsidianStorage storage() throws Exception {
        ObsidianStorage s = new ObsidianStorage(vault.toString(), "notes");
        s.init();
        return s;
    }

    @Test
    void write_creates_note_with_frontmatter() throws Exception {
        ObsidianStorage s = storage();
        s.write("hello", "Hello, world!");

        // read() strips frontmatter — body only
        Optional<String> content = s.read("hello");
        assertThat(content).isPresent();
        assertThat(content.get().strip()).isEqualTo("Hello, world!");

        // raw file should contain frontmatter
        Path file = vault.resolve("notes/hello.md");
        String raw = java.nio.file.Files.readString(file);
        assertThat(raw).contains("---");
        assertThat(raw).contains("updated:");
        assertThat(raw).contains("tags: [coach-bot]");
    }

    @Test
    void read_returns_empty_for_missing_note() throws Exception {
        ObsidianStorage s = storage();
        assertThat(s.read("nonexistent")).isEmpty();
    }

    @Test
    void append_creates_note_when_missing() throws Exception {
        ObsidianStorage s = storage();
        s.append("new-note", "First entry");
        assertThat(s.read("new-note")).isPresent();
        assertThat(s.read("new-note").get()).contains("First entry");
    }

    @Test
    void append_adds_divider_and_new_content() throws Exception {
        ObsidianStorage s = storage();
        s.write("log", "Initial content");
        s.append("log", "Second entry");

        String content = s.read("log").orElseThrow();
        assertThat(content).contains("Initial content");
        assertThat(content).contains("Second entry");
    }

    @Test
    void list_returns_all_md_files() throws Exception {
        ObsidianStorage s = storage();
        s.write("alpha", "A");
        s.write("beta", "B");
        s.write("subdir/gamma", "C");

        List<String> files = s.list("");
        assertThat(files).hasSize(3);
        assertThat(files).anyMatch(f -> f.endsWith("alpha.md"));
        assertThat(files).anyMatch(f -> f.endsWith("beta.md"));
        assertThat(files).anyMatch(f -> f.endsWith("gamma.md"));
    }

    @Test
    void delete_removes_note() throws Exception {
        ObsidianStorage s = storage();
        s.write("temp", "Temporary note");
        assertThat(s.read("temp")).isPresent();

        s.delete("temp");
        assertThat(s.read("temp")).isEmpty();
    }

    @Test
    void path_traversal_is_rejected() throws Exception {
        ObsidianStorage s = storage();
        assertThatThrownBy(() -> s.write("../evil", "bad"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traversal");
    }

    @Test
    void strip_frontmatter_removes_yaml_block() {
        String note = "---\ncreated: 2025-01-01\nupdated: 2025-01-01\n---\n\nThe body here.";
        assertThat(ObsidianStorage.stripFrontmatter(note)).isEqualTo("The body here.");
    }

    @Test
    void strip_frontmatter_returns_content_unchanged_when_no_frontmatter() {
        String note = "Just plain text, no frontmatter.";
        assertThat(ObsidianStorage.stripFrontmatter(note)).isEqualTo(note);
    }
}
