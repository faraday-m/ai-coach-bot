package dev.coachbot.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MemoryProgressParser}.
 */
class MemoryProgressParserTest {

    private static final String FULL_MEMORY = """
            ## Learning Plan
            - [x] Reactive Streams
            - [ ] Virtual Threads

            ## Topic Statistics
            | Topic | Sessions | Last seen | Level |
            |-------|----------|-----------|-------|
            | Reactive Streams | 5 | 2026-01-15 | ⬆️ Confident |
            | CompletableFuture | 3 | 2026-01-08 | ➡️ Familiar |
            | Virtual Threads | 1 | 2026-01-20 | ⬇️ Needs work |

            ## Preferences
            Prefers code examples.
            """;

    // ── parse() ───────────────────────────────────────────────────────────────

    @Test
    void parsesAllThreeTopics() {
        var topics = MemoryProgressParser.parse(FULL_MEMORY);
        assertThat(topics).hasSize(3);
    }

    @Test
    void parsesFieldsCorrectly() {
        var t = MemoryProgressParser.parse(FULL_MEMORY).get(0);
        assertThat(t.topic()).isEqualTo("Reactive Streams");
        assertThat(t.sessions()).isEqualTo(5);
        assertThat(t.lastSeen()).isEqualTo("2026-01-15");
        assertThat(t.level()).isEqualTo("⬆️ Confident");
    }

    @Test
    void fractionsByLevel() {
        var topics = MemoryProgressParser.parse(FULL_MEMORY);
        assertThat(topics.get(0).fraction()).isEqualTo(1.0);   // ⬆️ Confident
        assertThat(topics.get(1).fraction()).isEqualTo(0.6);   // ➡️ Familiar
        assertThat(topics.get(2).fraction()).isEqualTo(0.2);   // ⬇️ Needs work
    }

    @Test
    void emojisByLevel() {
        var topics = MemoryProgressParser.parse(FULL_MEMORY);
        assertThat(topics.get(0).emoji()).isEqualTo("⬆️");
        assertThat(topics.get(1).emoji()).isEqualTo("➡️");
        assertThat(topics.get(2).emoji()).isEqualTo("⬇️");
    }

    @Test
    void nullAndEmptyDocumentReturnEmptyList() {
        assertThat(MemoryProgressParser.parse(null)).isEmpty();
        assertThat(MemoryProgressParser.parse("")).isEmpty();
        assertThat(MemoryProgressParser.parse("No topic section here")).isEmpty();
    }

    @Test
    void stopsParsingAtNextSection() {
        String doc = """
                ## Topic Statistics
                | Topic | Sessions | Last seen | Level |
                |-------|----------|-----------|-------|
                | Java GC | 2 | 2026-01-10 | ➡️ Familiar |
                ## Coach Notes
                | Sneaky row | 1 | 2026-01-01 | ⬆️ Confident |
                """;
        var topics = MemoryProgressParser.parse(doc);
        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).topic()).isEqualTo("Java GC");
    }

    @Test
    void toleratesExtraWhitespace() {
        String doc = """
                ## Topic Statistics
                |  Topic  |  Sessions  |  Last seen  |  Level  |
                |---------|------------|-------------|---------|
                |  Kafka  |  2  |  2026-01-12  |  ➡️ Familiar  |
                """;
        var topics = MemoryProgressParser.parse(doc);
        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).topic()).isEqualTo("Kafka");
        assertThat(topics.get(0).sessions()).isEqualTo(2);
    }

    @Test
    void noTopicsSectionReturnsEmpty() {
        String doc = "## Learning Plan\n- [x] Something\n## Preferences\nprefers examples\n";
        assertThat(MemoryProgressParser.parse(doc)).isEmpty();
    }

    // ── formatAsText() ────────────────────────────────────────────────────────

    @Test
    void formatContainsAllTopicNames() {
        var topics = MemoryProgressParser.parse(FULL_MEMORY);
        String text = MemoryProgressParser.formatAsText(topics);
        assertThat(text).contains("Reactive Streams");
        assertThat(text).contains("CompletableFuture");
        assertThat(text).contains("Virtual Threads");
    }

    @Test
    void formatContainsProgressBlocks() {
        var topics = MemoryProgressParser.parse(FULL_MEMORY);
        String text = MemoryProgressParser.formatAsText(topics);
        assertThat(text).contains("█");   // filled block
        assertThat(text).contains("░");   // empty block
    }

    @Test
    void formatEmptyTopicsReturnsPlaceholder() {
        assertThat(MemoryProgressParser.formatAsText(List.of()))
                .isEqualTo("No topics tracked yet.");
    }

    @Test
    void formatSingleSessionGrammar() {
        var t = new MemoryProgressParser.TopicProgress("GC", 1, "2026-01-01", "⬇️ Needs work");
        assertThat(MemoryProgressParser.formatAsText(List.of(t))).contains("1 session");
        assertThat(MemoryProgressParser.formatAsText(List.of(t))).doesNotContain("1 sessions");
    }
}
