-- Agents (coaches): each is an independent session with its own LLM + storage + prompt
CREATE TABLE IF NOT EXISTS agents (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    system_prompt   TEXT,               -- actual prompt content (generated or edited)
    llm_backend     TEXT NOT NULL,      -- e.g. "claude", "ollama"
    storage_backend TEXT NOT NULL,      -- e.g. "filesystem", "obsidian"
    trigger         TEXT NOT NULL,      -- e.g. "@Andy"
    require_trigger INTEGER NOT NULL DEFAULT 1,  -- 0 = respond to any message
    enabled         INTEGER NOT NULL DEFAULT 1,
    created_at      TEXT,
    updated_at      TEXT
);

-- Which (transport, chat) each agent listens on (many-to-many)
CREATE TABLE IF NOT EXISTS agent_transports (
    agent_id     TEXT NOT NULL,
    transport_id TEXT NOT NULL,         -- e.g. "console", "telegram"
    chat_id      TEXT NOT NULL,         -- transport-specific chat id, e.g. "console" or "tg:-100123"
    PRIMARY KEY (agent_id, transport_id, chat_id),
    FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE
);

-- Cross-transport identity: maps transport-specific user ids to a canonical id
-- so the same conversation continues whether user writes from Telegram or Jabber
CREATE TABLE IF NOT EXISTS user_identities (
    transport_key TEXT PRIMARY KEY,     -- e.g. "tg:123456789", "jabber:me@xmpp.ru"
    canonical_id  TEXT NOT NULL,        -- e.g. "user:abc123"
    created_at    TEXT NOT NULL
);

-- Per-user conversation state per agent (session id, last activity)
CREATE TABLE IF NOT EXISTS conversations (
    canonical_user_id TEXT NOT NULL,
    agent_id          TEXT NOT NULL,
    session_id        TEXT,
    last_seen         TEXT,
    PRIMARY KEY (canonical_user_id, agent_id)
);

-- Conversation history (persisted across restarts)
CREATE TABLE IF NOT EXISTS messages (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    agent_id   TEXT    NOT NULL,
    user_id    TEXT    NOT NULL,   -- transport-specific sender id (e.g. "tg:123456")
    role       TEXT    NOT NULL,   -- USER | ASSISTANT
    content    TEXT    NOT NULL,
    created_at TEXT    NOT NULL
);
-- Lookup index: latest messages for a given (agent, user) pair
CREATE INDEX IF NOT EXISTS idx_messages_lookup
    ON messages (agent_id, user_id, id DESC);

-- Agent commands: slash-commands shown to the LLM as part of the system prompt.
-- When a user sends /quiz the LLM sees the command section and handles it accordingly.
CREATE TABLE IF NOT EXISTS agent_commands (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    agent_id    TEXT    NOT NULL,
    trigger     TEXT    NOT NULL,   -- e.g. "/quiz", "/hint"
    description TEXT    NOT NULL,   -- what the LLM should do when this command is sent
    enabled     INTEGER NOT NULL DEFAULT 1,
    created_at  TEXT    NOT NULL,
    UNIQUE(agent_id, trigger),
    FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE
);

-- Scheduled tasks: fire a prompt at a cron schedule and broadcast the LLM reply
-- to all transport/chat pairs this agent is registered on.
CREATE TABLE IF NOT EXISTS agent_schedules (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    agent_id    TEXT    NOT NULL,
    cron        TEXT    NOT NULL,   -- standard 5-part cron: "0 9 * * MON-FRI"
    prompt      TEXT    NOT NULL,   -- text sent to the LLM when this schedule fires
    save_path   TEXT,               -- optional: also save LLM response to storage at this path
                                    --   supports {date} placeholder → replaced with YYYY-MM-DD
    enabled     INTEGER NOT NULL DEFAULT 1,
    created_at  TEXT,
    FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE
);
-- Migration: add save_path to databases created before this column existed
ALTER TABLE agent_schedules ADD COLUMN IF NOT EXISTS save_path TEXT;
