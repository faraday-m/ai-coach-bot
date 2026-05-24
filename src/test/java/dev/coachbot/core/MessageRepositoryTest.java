package dev.coachbot.core;

import dev.coachbot.llm.ConversationMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MessageRepository} — runs against an in-memory SQLite DB,
 * no Spring context required.
 *
 * <p>Tests focus on the new tagged-append / loadAfter / lastMessageId / findMessages
 * methods added for the message-tagging feature.
 */
class MessageRepositoryTest {

    private static final String AGENT_ID = "test-agent";
    private static final String USER_ID  = "user:test123";

    private MessageRepository repo;

    @BeforeEach
    void setUp() {
        // SingleConnectionDataSource keeps the same JDBC connection open so that the
        // in-memory SQLite database (which lives only in that connection) is visible to
        // every JdbcTemplate operation within the test.
        var ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var jdbc = new JdbcTemplate(ds);
        // Minimal schema — mirrors schema.sql messages table
        jdbc.execute("""
                CREATE TABLE messages (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    agent_id     TEXT NOT NULL,
                    user_id      TEXT NOT NULL,
                    role         TEXT NOT NULL,
                    content      TEXT NOT NULL,
                    created_at   TEXT NOT NULL,
                    transport_id TEXT,
                    trigger_type TEXT
                )
                """);
        repo = new MessageRepository(jdbc);
    }

    // ── Tagged append ─────────────────────────────────────────────────────────

    @Test
    void appendWithMetadataPersistsTransportAndTrigger() {
        repo.append(AGENT_ID, USER_ID,
                ConversationMessage.user("hello"), "telegram", "user_message");

        List<MessageRepository.MessageRow> rows =
                repo.findMessages(AGENT_ID, null, null, 10);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).transportId()).isEqualTo("telegram");
        assertThat(rows.get(0).triggerType()).isEqualTo("user_message");
        assertThat(rows.get(0).role()).isEqualTo("USER");
        assertThat(rows.get(0).content()).isEqualTo("hello");
    }

    @Test
    void legacyAppendLeavesMetadataNull() {
        // The 3-arg overload should still work; transport/trigger remain NULL
        repo.append(AGENT_ID, USER_ID, ConversationMessage.assistant("hi"));

        List<MessageRepository.MessageRow> rows =
                repo.findMessages(AGENT_ID, null, null, 10);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).transportId()).isNull();
        assertThat(rows.get(0).triggerType()).isNull();
    }

    // ── loadAfter ────────────────────────────────────────────────────────────

    @Test
    void loadAfterReturnsOnlyNewerMessages() {
        repo.append(AGENT_ID, USER_ID, ConversationMessage.user("msg1"), "telegram", "user_message");
        long afterId = repo.lastMessageId(AGENT_ID, USER_ID);
        repo.append(AGENT_ID, USER_ID, ConversationMessage.user("msg2"), "telegram", "user_message");
        repo.append(AGENT_ID, USER_ID, ConversationMessage.user("msg3"), "telegram", "user_message");

        List<ConversationMessage> result = repo.loadAfter(AGENT_ID, USER_ID, afterId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).content()).isEqualTo("msg2");
        assertThat(result.get(1).content()).isEqualTo("msg3");
    }

    @Test
    void loadAfterZeroReturnsAllMessages() {
        repo.append(AGENT_ID, USER_ID, ConversationMessage.user("a"), "telegram", "user_message");
        repo.append(AGENT_ID, USER_ID, ConversationMessage.user("b"), "telegram", "user_message");

        List<ConversationMessage> result = repo.loadAfter(AGENT_ID, USER_ID, 0L);

        assertThat(result).hasSize(2);
    }

    @Test
    void loadAfterReturnsEmptyWhenNothingNew() {
        repo.append(AGENT_ID, USER_ID, ConversationMessage.user("only"), "telegram", "user_message");
        long afterId = repo.lastMessageId(AGENT_ID, USER_ID);

        assertThat(repo.loadAfter(AGENT_ID, USER_ID, afterId)).isEmpty();
    }

    // ── lastMessageId ────────────────────────────────────────────────────────

    @Test
    void lastMessageIdReturnsZeroWhenNoMessages() {
        assertThat(repo.lastMessageId(AGENT_ID, USER_ID)).isEqualTo(0L);
    }

    @Test
    void lastMessageIdReturnsMaxId() {
        repo.append(AGENT_ID, USER_ID, ConversationMessage.user("first"),  "telegram", "user_message");
        repo.append(AGENT_ID, USER_ID, ConversationMessage.user("second"), "telegram", "user_message");
        long lastId = repo.lastMessageId(AGENT_ID, USER_ID);

        // The second row should have a higher ID
        List<ConversationMessage> after = repo.loadAfter(AGENT_ID, USER_ID, lastId);
        assertThat(after).isEmpty();   // nothing after the last ID
    }

    // ── findMessages filters ──────────────────────────────────────────────────

    @Test
    void findMessagesFiltersCorrectly() {
        repo.append(AGENT_ID, USER_ID, ConversationMessage.user("t1"), "telegram", "user_message");
        repo.append(AGENT_ID, USER_ID, ConversationMessage.user("t2"), "telegram", "user_command");
        repo.append(AGENT_ID, USER_ID, ConversationMessage.user("w1"), "webchat",  "user_message");

        // no filter → all 3
        assertThat(repo.findMessages(AGENT_ID, null, null, 10)).hasSize(3);

        // transport filter
        assertThat(repo.findMessages(AGENT_ID, "telegram", null, 10)).hasSize(2);
        assertThat(repo.findMessages(AGENT_ID, "webchat",  null, 10)).hasSize(1);

        // trigger filter
        assertThat(repo.findMessages(AGENT_ID, null, "user_message", 10)).hasSize(2);
        assertThat(repo.findMessages(AGENT_ID, null, "user_command", 10)).hasSize(1);

        // combined filter
        assertThat(repo.findMessages(AGENT_ID, "telegram", "user_message", 10)).hasSize(1);
        assertThat(repo.findMessages(AGENT_ID, "telegram", "user_message", 10)
                .get(0).content()).isEqualTo("t1");
    }

    @Test
    void findMessagesRespectLimit() {
        for (int i = 0; i < 5; i++) {
            repo.append(AGENT_ID, USER_ID,
                    ConversationMessage.user("msg" + i), "telegram", "user_message");
        }

        assertThat(repo.findMessages(AGENT_ID, null, null, 3)).hasSize(3);
    }

    @Test
    void findMessagesIsolatedByAgentId() {
        repo.append(AGENT_ID,   USER_ID, ConversationMessage.user("mine"),   "telegram", "user_message");
        repo.append("other-bot", USER_ID, ConversationMessage.user("theirs"), "telegram", "user_message");

        assertThat(repo.findMessages(AGENT_ID, null, null, 10)).hasSize(1);
        assertThat(repo.findMessages(AGENT_ID, null, null, 10).get(0).content()).isEqualTo("mine");
    }
}
