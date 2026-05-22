# ai-coach-bot

An extensible AI coaching bot. One image, multiple coaching agents (Java interview prep, English practice, system design, etc.), pluggable LLM backends and transports.

```
User (Telegram / Jabber / Console / Web chat)
        │
        ▼
  Orchestrator  ──  trigger "@Andy" ──▶  GroupSession [java-coach]
                                               │
                                  ┌────────────┼────────────┐
                               LLM          History      Storage
                           (Claude /        (SQLite)   (Markdown /
                           Gemini /                     Obsidian)
                           OpenAI …)
                               │
                    ┌──────────┴──────────┐
                 Agent Loop           Memory
               (tool calls)      (per-user .md)
```

---

## Requirements

- **Docker** and **Docker Compose** — that's it.

No Java, no Node, no local toolchain needed to run the bot.  
_(Java 21 + Maven are only required if you want to [build from source](#building-from-source).)_

---

## Quick start

### 1. Clone

```bash
git clone https://github.com/faraday-m/ai-coach-bot.git
cd ai-coach-bot
```

### 2. Run the setup wizard (recommended)

An interactive shell wizard walks you through everything — LLM choice, Telegram, admin credentials, language, Obsidian vault — and writes a ready-to-use `.env`:

```bash
./setup.sh
```

The wizard also runs the onboarding questionnaire and starts the bot automatically.

### Manual setup

#### 2a. Create `.env`

```bash
# Pick one LLM backend (Claude recommended — supports tool use and streaming):

# Anthropic Claude
ANTHROPIC_API_KEY=sk-ant-api03-...
BOT_LLM_CLAUDE_ENABLED=true
BOT_DEFAULT_LLM_BACKEND=claude

# — or — Google Gemini (free key at aistudio.google.com)
# GEMINI_API_KEY=AIza...
# BOT_LLM_GEMINI_ENABLED=true
# BOT_DEFAULT_LLM_BACKEND=gemini

# — or — OpenAI
# OPENAI_API_KEY=sk-...
# BOT_LLM_OPENAI_ENABLED=true
# BOT_DEFAULT_LLM_BACKEND=openai

# — or — Local Ollama (no key needed)
# BOT_LLM_OLLAMA_ENABLED=true
# BOT_DEFAULT_LLM_BACKEND=ollama
```

> `BOT_DEFAULT_LLM_BACKEND` is required on the **first run** (empty database) and when
> switching LLMs. After that the agent's backend is persisted in SQLite.

#### 2b. Connect Telegram (optional)

Get a bot token from [@BotFather](https://t.me/BotFather) (`/newbot`).

Add to `.env`:

```bash
TELEGRAM_BOT_TOKEN=123456789:AAF...
BOT_TRANSPORTS_TELEGRAM_ENABLED=true
BOT_TRANSPORTS_CONSOLE_ENABLED=false
```

The bot automatically registers available slash-commands in the Telegram command menu for each chat — users see `/wiki`, `/memory`, `/onboard` and any custom commands added via the admin UI. LLM responses with Markdown formatting are rendered natively in Telegram.

#### 2c. Start

```bash
docker compose up -d
docker compose logs -f coach-bot
```

---

## Web Chat Frontend

A lightweight React chat UI is available as an optional Docker profile. It connects to the bot over SSE (Server-Sent Events) and requires no extra infrastructure.

```bash
BOT_WEBCHAT_ENABLED=true docker compose --profile webchat up -d
```

Open **http://localhost:3000**.

| Env var | Default | Description |
|---|---|---|
| `BOT_WEBCHAT_ENABLED` | `false` | Enable the REST + SSE backend |
| `BOT_CHAT_PORT` | `3000` | Host port for the React frontend |
| `VITE_AGENT_ID` | `default` | Which agent the UI talks to (must match an agent ID in the DB) |

The UI includes a **command picker**: type `/` to browse all available commands (system + agent-specific). Session state is stored in `localStorage` — history survives page refresh.

For local development without Docker:

```bash
cd frontend
npm install
npm run dev   # → http://localhost:5173, proxies /api to :8080
```

---

## Onboarding — generating a personalised system prompt

The onboarding wizard asks 9 questions and calls the LLM to generate a tailored coaching system prompt. After the last answer, a second LLM call automatically generates topic-specific slash-commands based on the learner's profile — they appear in the Telegram command menu and web chat picker immediately.

```bash
docker compose run --rm coach-bot --mode=onboard
```

```
╔═══════════════════════════════════════════════════╗
║        Coach-bot Onboarding Wizard                ║
╚═══════════════════════════════════════════════════╝

Configuring agent: default (My Coach)

[1/9] What topic or skill do you want to practise?
> Java interviews

[2/9] What is your current level in this area?
> 5 years Java, strong on collections and JVM internals

[3/9] What are your strengths?
> Distributed systems, JVM tuning

[4/9] What areas feel hardest or most important to improve?
> System Design vocabulary, concurrency edge cases

[5/9] What is your goal?
> Pass a Staff Engineer interview at a FAANG

[6/9] What language should the coach use?
> Russian

[7/9] What tone do you prefer from the coach?
> Casual and friendly

[8/9] How detailed should the answers be?
> Thorough with examples and explanations

[9/9] How long should the coach work through a single topic with you?
> deep dive — keep going until the topic is fully covered

─── Generated system prompt ─────────────────────────
You are an expert Java and system design coach...
─────────────────────────────────────────────────────

Save this prompt to agent 'default'? [Y/n] y
✓ System prompt saved (1842 chars).

📋 Commands generated for your topics:
  /core       — Java Core — collections, concurrency, JVM
  /reactive   — Reactive programming deep dive
  /database   — Database design questions
  /algorithms — Algorithm & data structure practice
  /design     — System design and architecture
```

Users can also trigger onboarding in chat:

```
@Andy /onboard
```

### Translating onboarding questions

Set `BOT_LANGUAGE` in `.env` to translate questions automatically via
[MyMemory](https://mymemory.translated.net) — free, no account required:

```bash
BOT_LANGUAGE=ru   # or de, es, fr, zh-cn, ja, …
```

Optionally provide your e-mail to raise the free tier limit from 5k to 10k chars/day:

```bash
BOT_TRANSLATE_API_KEY=you@example.com
```

---

## Web Admin UI

Open **http://localhost:8080** after starting the bot.

Default credentials: `admin` / `coach-bot`  
Change them in `.env`:

```bash
BOT_WEB_USERNAME=yourusername
BOT_WEB_PASSWORD=a-strong-password
```

| Tab | What you can do |
|---|---|
| **Agents list** | See all agents, create new, enable/disable |
| **Overview** | Edit name, trigger, require-trigger; toggle enabled |
| **System Prompt** | Edit and save — applied immediately without restart |
| **Commands** | Add / edit / delete `/quiz`, `/hint` etc. — applied immediately |
| **Transports** | Add / edit / remove (transport, chatId) bindings |
| **Schedules** | Add / edit / delete cron schedules with optional auto-save path |

All changes take effect immediately — no restart required.

---

## Built-in commands

These commands are available in every agent regardless of configuration:

| Command | Description |
|---|---|
| `/wiki <path>` | Summarise the conversation since the last `/wiki` and save to storage |
| `/wiki <path> <instruction>` | Same, but follow a custom directive |
| `/memory` | Show the current learning memory for this user |
| `/memory add <text>` | Append a note directly (no LLM call) |
| `/memory reset` | Delete the memory file for this user |
| `/onboard` | Restart the onboarding questionnaire |

Agent-specific commands (e.g. `/quiz`, `/hint`) are defined per-agent in the Admin UI → Commands tab and appear alongside system commands in the Telegram menu and web chat command picker.

---

## Knowledge capture — `/wiki`

At any point in a conversation you can ask the bot to distil what was covered into a Markdown note and save it to the agent's storage backend.

```
/wiki notes/virtual-threads
```

The LLM reads the conversation **since your last `/wiki` call** and produces a structured note. The checkpoint advances after each save — the next `/wiki` captures only the new part of the conversation.

### With an instruction

```
/wiki notes/gc  Напиши подробную статью про сборщик мусора в Java
```

### Multiple files

```
/wiki notes/  Сохрани GC и virtual threads как отдельные статьи
```

Bot reply:
```
✅ Saved: `notes/gc-algorithms.md`, `notes/virtual-threads.md`
```

---

## Agent Memory

After each `/wiki` save the bot automatically builds a **learning memory** document for the user — a concise Markdown file tracking topic progress, session statistics, observed preferences, and coach notes.

The memory is injected into every LLM request as hidden context, so the bot remembers where you left off without you having to repeat yourself.

```
/memory               — show the current memory document
/memory add <text>    — append a note directly (no LLM call)
/memory reset         — clear all memory for this agent
```

Memory files are stored at `coach-bot/memory/<agentId>/<userId>.md` in the agent's storage backend.

---

## Tool Use (Claude backend)

When using the Claude backend the bot operates as an **agent loop**: it can call tools to read, write, and list files before returning a final answer.

```
You: What topics are in my notes/?
Bot: [calls list_files("notes/")] → [calls read_file("notes/virtual-threads.md")] → answers
```

Available tools:

| Tool | What it does |
|---|---|
| `read_file` | Read a file from the agent's storage backend |
| `write_file` | Write or overwrite a file |
| `list_files` | List files under a path prefix |

The loop runs for up to 10 steps. Other backends (Gemini, OpenAI, Ollama) do not use the agent loop.

---

## Scheduled broadcasts

```bash
docker compose run --rm coach-bot --mode=manage
```

```
> schedule list default
> schedule add default "0 9 * * MON-FRI" Give me a morning Java question
> schedule enable 1  /  schedule disable 1  /  schedule rm 1
```

Cron format — standard 5-field Unix cron: `minute hour day month weekday`

```
"0 9 * * MON-FRI"   — weekdays at 09:00
"30 18 * * *"        — every day at 18:30
```

### Auto-save to storage

Set a **Save to path** to automatically persist the LLM response after each broadcast:

```
> schedule add default "0 20 * * *" "Summarise what I should review tomorrow" journal/{date}
```

The `{date}` placeholder is replaced with the current date (`YYYY-MM-DD`). The response is **appended** with a timestamp heading — multiple firings per day accumulate in the same file.

---

## Configuration

Configuration is layered — **later sources override earlier ones**:

| Source | How |
|---|---|
| Built-in defaults | Baked into the image (`classpath:application.yml`) |
| Config file | `./config/application.yml` — mounted into the container |
| Environment variables | `.env` file or `environment:` in `docker-compose.yml` |

Any `application.yml` property can be overridden via env var: replace `.` with `_`, uppercase everything.

```
bot.llm.claude.enabled              →  BOT_LLM_CLAUDE_ENABLED
bot.llm.gemini.model                →  BOT_LLM_GEMINI_MODEL
bot.transports.telegram.enabled     →  BOT_TRANSPORTS_TELEGRAM_ENABLED
bot.transports.webchat.enabled      →  BOT_WEBCHAT_ENABLED
bot.language                        →  BOT_LANGUAGE
```

### LLM backends

All backends are **disabled by default**.

| Backend | Key env var | Notes |
|---|---|---|
| `claude` | `ANTHROPIC_API_KEY` | Supports native tool use and streaming. Recommended for agentic features. |
| `gemini` | `GEMINI_API_KEY` | Free at [aistudio.google.com](https://aistudio.google.com/app/apikey) |
| `openai` | `OPENAI_API_KEY` | Also works with Groq, OpenRouter, LM Studio via `BOT_LLM_OPENAI_BASE_URL` |
| `ollama` | *(none)* | Local models — set `BOT_LLM_OLLAMA_BASE_URL` (default: `http://host.docker.internal:11434`) |

### Transports

| Transport | Key env var | Notes |
|---|---|---|
| `console` | — | Stdin/stdout — enabled by default for local testing |
| `telegram` | `TELEGRAM_BOT_TOKEN` | Enable with `BOT_TRANSPORTS_TELEGRAM_ENABLED=true`. Registers slash-command menus per chat. Markdown rendered natively. |
| `jabber` | `BOT_JABBER_USERNAME` | XMPP — enable with `BOT_TRANSPORTS_JABBER_ENABLED=true` |
| `webchat` | — | SSE-based REST transport for the React frontend. Enable with `BOT_WEBCHAT_ENABLED=true`. |

Jabber config:

```bash
BOT_TRANSPORTS_JABBER_ENABLED=true
BOT_JABBER_SERVER=jabber.org
BOT_JABBER_HOST=                      # optional: direct IP if DNS doesn't resolve SRV
BOT_JABBER_PORT=5222
BOT_JABBER_USERNAME=bot@jabber.org
BOT_JABBER_PASSWORD=secret
BOT_JABBER_ACCEPT_ALL_CERTS=false     # true only for self-signed local servers
```

### Storage backends

| Backend | Notes |
|---|---|
| `filesystem` | Plain Markdown in `./notes` — default, always available |
| `obsidian` | Markdown with YAML frontmatter written into your Obsidian vault |

**Enable Obsidian storage:**

```bash
BOT_STORAGE_OBSIDIAN_ENABLED=true
OBSIDIAN_VAULT_PATH=/Users/you/Documents/MyVault
```

Notes are written to a `coach-bot/` subfolder inside the vault. Override:

```yaml
# config/application.yml
bot:
  storage:
    obsidian:
      notes-subfolder: "AI Coach"
```

### Initial agent name

```bash
BOT_AGENT_NAME="Java Interview Coach"   # default: "My Coach"
```

This only affects the initial database seed. Rename via Admin UI → Overview tab.

### Local Ollama

```bash
BOT_LLM_OLLAMA_ENABLED=true
BOT_DEFAULT_LLM_BACKEND=ollama
# BOT_LLM_OLLAMA_BASE_URL=http://host.docker.internal:11434  (default)
# BOT_LLM_OLLAMA_MODEL=gemma3:12b  (default)
# BOT_LLM_OLLAMA_TIMEOUT_SECONDS=300
```

---

## Managing agents

```bash
docker compose run --rm coach-bot --mode=manage
```

```
> agent list
> agent show default
> agent edit default
> agent create
> agent add-transport default telegram -1001234567890
> agent rm-transport  default telegram -1001234567890

> command list default
> command add default /quiz "Ask a random Java interview question"
> command enable 1  /  command disable 1  /  command rm 1

> schedule list default
> schedule add default "0 9 * * MON-FRI" Give me a morning Java question
> schedule enable 1  /  schedule disable 1  /  schedule rm 1

> help
> exit
```

---

## Multiple agents

```yaml
# config/application.yml
seeds:
  agents:
    - id: java-coach
      name: "Java Interview Coach"
      system-prompt-classpath: "prompts/java-coach.md"
      llm-backend: claude
      storage-backend: filesystem
      trigger: "@Andy"
      require-trigger: true
      transports:
        - transport-id: telegram
          chat-id: "-1001234567890"

    - id: english-coach
      name: "English Coach"
      system-prompt: "You are an English language coach..."
      llm-backend: gemini
      storage-backend: obsidian
      trigger: "@English"
      require-trigger: true
      transports:
        - transport-id: telegram
          chat-id: "-1001234567890"   # same group, different trigger
```

In a group chat: `@Andy tell me about generics` → `java-coach`, `@English correct my sentence` → `english-coach`.

---

## Credential providers

**HashiCorp Vault:**

```yaml
bot:
  credentials:
    vault:
      enabled: true
      url: "${VAULT_ADDR:http://localhost:8200}"
      token: "${VAULT_TOKEN}"
      secret-path: "secret/data/coach-bot"
  llm:
    claude:
      enabled: true
      credential-provider: vault
      api-key-name: ANTHROPIC_API_KEY
```

**Transparent proxy (OneCLI / LiteLLM):**

```yaml
bot:
  llm:
    claude:
      enabled: true
      base-url: "http://localhost:10254/anthropic"
```

---

## Volume layout

| Mount | Purpose |
|---|---|
| `./data`   | SQLite database (`bot.db`) — agents, history, schedules |
| `./notes`  | Markdown notes (filesystem storage) |
| `./config` | `application.yml` overrides |
| `$OBSIDIAN_VAULT_PATH` | Obsidian vault — mounted at `/obsidian` inside the container |

---

## Launch modes

| Flag | What it does |
|---|---|
| *(none)* | Normal bot — message polling + scheduled tasks |
| `--mode=manage` | Interactive agent and schedule management CLI |
| `--mode=onboard` | Generate a personalised system prompt |

---

## Project structure

```
src/main/java/dev/coachbot/
├── cli/            ManageCli              — --mode=manage interactive console
├── config/         SeedProperties         — YAML → SQLite seed on first run
├── core/           Orchestrator           — message routing + webchat dispatch
│                   GroupSession           — per-agent conversation loop + commands
│                   SchemaMigration        — programmatic SQLite column migrations
│                   AgentRepository        — SQLite CRUD for agents
│                   CommandRepository      — SQLite CRUD for slash-commands
│                   MessageRepository      — conversation history (SQLite)
│                   UserIdentityRepository — cross-transport identity mapping
├── credentials/    env / vault / onecli   — pluggable credential providers
├── llm/            LlmBackend / LlmRequest / LlmResponse / ConversationMessage
│                   Tool / ToolDefinition / ToolCall / ToolResult  — tool-use SPI
│                   claude / gemini / openai / ollama
├── memory/         MemoryService          — per-user learning memory (async update)
├── onboarding/     OnboardingFlow         — 9-step FSM → LLM-generated system prompt + commands
│                   OnboardWizard          — --mode=onboard CLI
├── scheduler/      AgentScheduler         — cron-based broadcast messages
│                   ScheduleRepository     — SQLite CRUD for schedules
├── storage/        filesystem / obsidian
├── tool/           WriteFileTool / ReadFileTool / ListFilesTool
├── translation/    MyMemoryClient         — MyMemory free translation API
│                   TranslationService     — translate + cache + English fallback
├── transport/      console / telegram / jabber
│                   webchat/               — SSE transport + REST controller
└── web/            Vaadin admin UI (AgentsView, AgentDetailView, …)

frontend/                                  — React web chat (Vite + TypeScript + Tailwind)
```

New LLM backend: implement `LlmBackend`, annotate `@Component`.  
New transport: implement `TransportPlugin`, annotate `@Component`.  
New storage backend: implement `StorageBackend`, annotate `@Component`.  
Spring auto-detects and registers all three — no other changes needed.

---

## Building from source

Requires **Java 21** and **Maven**.

```bash
mvn package -DskipTests
mvn test

# Run locally without Docker:
export ANTHROPIC_API_KEY=sk-ant-...
export BOT_LLM_CLAUDE_ENABLED=true
export BOT_DEFAULT_LLM_BACKEND=claude
java -jar target/coach-bot.jar

java -jar target/coach-bot.jar --mode=manage
java -jar target/coach-bot.jar --mode=onboard
```

Frontend (local dev, requires Node 20+):

```bash
cd frontend
npm install
npm run dev   # Vite dev server at :5173, proxies /api to :8080
```

---

## License

MIT
