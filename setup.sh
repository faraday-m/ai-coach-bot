#!/usr/bin/env bash
# Coach-bot interactive setup wizard
# Tested on macOS and Linux; requires Docker with Compose plugin (v2).
set -euo pipefail

# ── Colours ────────────────────────────────────────────────────────────────────
# ANSI-C quoting ($'...') so variables contain actual ESC bytes, not literal text.
R=$'\033[0;31m'   # red
G=$'\033[0;32m'   # green
Y=$'\033[1;33m'   # yellow
C=$'\033[0;36m'   # cyan
B=$'\033[1m'      # bold
D=$'\033[2m'      # dim
NC=$'\033[0m'     # reset

DOCKER_CMD=""    # set by detect_docker()

# ── Helpers ────────────────────────────────────────────────────────────────────

ok()   { printf "  ${G}✓${NC}  %s\n" "$*"; }
info() { printf "  ${C}→${NC}  %s\n" "$*"; }
warn() { printf "  ${Y}⚠${NC}  %s\n" "$*"; }
err()  { printf "  ${R}✗${NC}  %s\n" "$*" >&2; }
die()  { err "$*"; exit 1; }

hr()   { printf "${D}%s${NC}\n" "────────────────────────────────────────────────────"; }

ask() {
    # ask <var> <prompt> [default]
    local var="$1" prompt="$2" default="${3:-}"
    local display_default=""
    [[ -n "$default" ]] && display_default=" ${D}[${default}]${NC}"
    printf "  ${Y}?${NC}  %b%b: " "$prompt" "$display_default"
    local input
    read -re input
    if [[ -z "$input" && -n "$default" ]]; then
        printf -v "$var" '%s' "$default"
    else
        printf -v "$var" '%s' "$input"
    fi
}

ask_secret() {
    # ask_secret <var> <prompt>
    local var="$1" prompt="$2"
    printf "  ${Y}?${NC}  %s: " "$prompt"
    local input
    read -rs input
    echo
    printf -v "$var" '%s' "$input"
}

ask_yn() {
    # ask_yn <prompt> [default: y|n] — returns 0 for yes, 1 for no
    local prompt="$1" default="${2:-y}"
    local hint
    [[ "$default" == "y" ]] && hint="${B}Y${NC}/n" || hint="y/${B}N${NC}"
    printf "  ${Y}?${NC}  %s [%b]: " "$prompt" "$hint"
    local input
    read -re input
    input="${input:-$default}"
    case "$input" in [yY]*) return 0 ;; *) return 1 ;; esac
}

menu() {
    # menu <result_var> <title> item1 "desc1" item2 "desc2" ...
    local result_var="$1" title="$2"; shift 2
    local items=("$@")
    local count=$(( ${#items[@]} / 2 ))
    printf "\n  ${B}%s${NC}\n\n" "$title"
    for (( i=0; i<count; i++ )); do
        printf "    ${C}%d${NC})  %-22s  ${D}%s${NC}\n" \
            $(( i+1 )) "${items[$((i*2))]}" "${items[$((i*2+1))]}"
    done
    echo
    local choice
    while true; do
        printf "  ${Y}?${NC}  Choice [1]: "
        read -re choice
        choice="${choice:-1}"
        if [[ "$choice" =~ ^[0-9]+$ ]] && (( choice >= 1 && choice <= count )); then
            printf -v "$result_var" '%s' "${items[$((( choice-1 )*2))]}"
            return
        fi
        warn "Please enter a number between 1 and $count."
    done
}

spinner() {
    # spinner <pid> <message>
    local pid=$1 msg="$2"
    local frames=('⠋' '⠙' '⠹' '⠸' '⠼' '⠴' '⠦' '⠧' '⠇' '⠏')
    local i=0
    printf "  ${C}%s${NC}  %s" "${frames[0]}" "$msg"
    while kill -0 "$pid" 2>/dev/null; do
        printf "\r  ${C}%s${NC}  %s" "${frames[$((i % ${#frames[@]}))]}" "$msg"
        (( i++ ))
        sleep 0.1
    done
    printf "\r  ${G}✓${NC}  %s\n" "$msg"
}

step_header() {
    echo
    hr
    printf "  ${B}${C}%s${NC}\n" "$*"
    hr
}

# ── Prerequisites ──────────────────────────────────────────────────────────────

detect_docker() {
    if docker compose version &>/dev/null; then
        DOCKER_CMD="docker compose"
    elif docker-compose version &>/dev/null; then
        DOCKER_CMD="docker-compose"
    else
        die "Docker Compose not found. Install Docker Desktop or the Compose plugin."
    fi
    if ! docker info &>/dev/null; then
        die "Docker daemon is not running. Start Docker and try again."
    fi
}

check_prerequisites() {
    step_header "Checking prerequisites"
    command -v docker &>/dev/null || die "Docker not found. Install Docker Desktop first."
    detect_docker
    ok "Docker: $(docker --version)"
    ok "Compose: $($DOCKER_CMD version --short 2>/dev/null || echo 'ok')"
}

# ── Configuration steps ────────────────────────────────────────────────────────

step_llm() {
    step_header "Step 1 / 5 — LLM backend"
    printf "  ${D}Choose which AI model powers your coaching bot.${NC}\n"

    menu LLM_BACKEND "Select a backend:" \
        "gemini"     "Google Gemini — free key at aistudio.google.com  ★ recommended" \
        "claude"     "Anthropic Claude — best quality, paid" \
        "openai"     "OpenAI — GPT-4o and compatible" \
        "openrouter" "OpenRouter — free + paid models, one key" \
        "ollama"     "Local Ollama — no key needed, runs on your hardware"

    case "$LLM_BACKEND" in
        gemini)
            ask_secret GEMINI_API_KEY "Gemini API key (aistudio.google.com → Get API key)"
            [[ -z "$GEMINI_API_KEY" ]] && die "Gemini API key is required."
            ANTHROPIC_API_KEY="" OPENAI_API_KEY="" OPENAI_BASE_URL="" OPENAI_MODEL=""
            ;;
        claude)
            ask_secret ANTHROPIC_API_KEY "Anthropic API key (sk-ant-api03-…)"
            [[ -z "$ANTHROPIC_API_KEY" ]] && die "Anthropic API key is required."
            GEMINI_API_KEY="" OPENAI_API_KEY="" OPENAI_BASE_URL="" OPENAI_MODEL=""
            ;;
        openai)
            ask_secret OPENAI_API_KEY "OpenAI API key (sk-…)"
            [[ -z "$OPENAI_API_KEY" ]] && die "OpenAI API key is required."
            ask OPENAI_MODEL "Model name" "gpt-4o"
            GEMINI_API_KEY="" ANTHROPIC_API_KEY="" OPENAI_BASE_URL=""
            ;;
        openrouter)
            ask_secret OPENAI_API_KEY "OpenRouter API key (sk-or-…)"
            [[ -z "$OPENAI_API_KEY" ]] && die "OpenRouter API key is required."
            ask OPENAI_MODEL "Model (see openrouter.ai/models)" "meta-llama/llama-3.3-70b-instruct:free"
            OPENAI_BASE_URL="https://openrouter.ai/api/v1"
            LLM_BACKEND="openai"   # same backend, different base URL
            GEMINI_API_KEY="" ANTHROPIC_API_KEY=""
            ;;
        ollama)
            ask OLLAMA_URL "Ollama URL" "http://host.docker.internal:11434"
            ask OLLAMA_MODEL "Model name (pull it first with: ollama pull …)" "gemma3:12b"
            GEMINI_API_KEY="" ANTHROPIC_API_KEY="" OPENAI_API_KEY="" OPENAI_BASE_URL="" OPENAI_MODEL=""
            ;;
    esac
    ok "Backend: $LLM_BACKEND"
}

step_telegram() {
    step_header "Step 2 / 5 — Telegram (optional)"
    printf "  ${D}Skip this step if you only want to use the web admin UI or console.${NC}\n\n"

    TELEGRAM_ENABLED=false
    TELEGRAM_TOKEN=""

    if ask_yn "Connect a Telegram bot?" "n"; then
        printf "\n  ${D}Get a token from @BotFather → /newbot${NC}\n\n"
        ask_secret TELEGRAM_TOKEN "Telegram bot token (123456:ABC-…)"
        if [[ -z "$TELEGRAM_TOKEN" ]]; then
            warn "No token entered — skipping Telegram."
        else
            TELEGRAM_ENABLED=true
            ok "Telegram will be enabled."
            info "After setup: add the bot to a chat and send /start"
        fi
    else
        ok "Skipped — you can add Telegram later via the admin UI."
    fi
}

step_admin() {
    step_header "Step 3 / 5 — Admin UI & agent name"
    printf "  ${D}Web admin will be available at http://localhost:8080${NC}\n\n"

    ask AGENT_NAME "Coach display name (shown in the UI and to users)" "My Coach"
    ask WEB_USER "Admin username" "admin"
    ask_secret WEB_PASS "Admin password (leave blank to use default 'coach-bot')"
    [[ -z "$WEB_PASS" ]] && WEB_PASS="coach-bot" && warn "Using default password 'coach-bot' — change it in .env later."
    ask WEB_PORT "Port" "8080"
    ok "Agent: ${AGENT_NAME}  |  Admin UI → http://localhost:${WEB_PORT}  (user: ${WEB_USER})"
}

_detect_timezone() {
    # macOS and most Linux distros expose the timezone via /etc/localtime symlink
    if [[ -L /etc/localtime ]]; then
        readlink /etc/localtime | sed 's|.*/zoneinfo/||'
        return
    fi
    # Debian/Ubuntu
    if [[ -f /etc/timezone ]]; then
        cat /etc/timezone
        return
    fi
    echo "UTC"
}

step_language() {
    step_header "Step 4 / 5 — Language, timezone & translation"
    printf "  ${D}The onboarding questions can be translated to your language.${NC}\n"
    printf "  ${D}Uses MyMemory free API — no key required.${NC}\n\n"

    local tz_default
    tz_default="$(_detect_timezone)"
    ask BOT_TIMEZONE "Timezone for cron schedules (IANA name)" "$tz_default"
    ok "Timezone: ${BOT_TIMEZONE}"

    ask BOT_LANGUAGE "Bot language (en = no translation, ru / de / es / fr / …)" "en"
    TRANSLATE_EMAIL=""
    if [[ "$BOT_LANGUAGE" != "en" ]]; then
        ok "Translation to '$BOT_LANGUAGE' enabled (MyMemory free tier)."
        printf "\n  ${D}Optional: enter your e-mail to raise the rate limit to 10k chars/day.${NC}\n"
        ask TRANSLATE_EMAIL "E-mail for MyMemory (or leave blank)" ""
    else
        ok "Language: English (no translation)."
    fi
}

step_obsidian() {
    step_header "Step 5 / 5 — Obsidian vault (optional)"
    printf "  ${D}Saves coaching notes as Markdown files inside your Obsidian vault.${NC}\n\n"

    OBSIDIAN_ENABLED=false
    OBSIDIAN_PATH=""

    if ask_yn "Write notes to an Obsidian vault?" "n"; then
        ask OBSIDIAN_PATH "Absolute path to your vault" ""
        if [[ -z "$OBSIDIAN_PATH" || ! -d "$OBSIDIAN_PATH" ]]; then
            warn "Path not found — Obsidian storage disabled. Set OBSIDIAN_VAULT_PATH in .env later."
        else
            OBSIDIAN_ENABLED=true
            ok "Obsidian vault: $OBSIDIAN_PATH"
        fi
    else
        ok "Skipped — notes will be saved as plain Markdown in ./notes"
    fi
}

# ── Review & write .env ────────────────────────────────────────────────────────

# Pre-compute display string for LLM section (no multi-line subshell in output block).
_llm_preview() {
    case "$LLM_BACKEND" in
        gemini)     echo "GEMINI_API_KEY=**** (set)" ;;
        claude)     echo "ANTHROPIC_API_KEY=**** (set)" ;;
        openai)     echo "OPENAI_API_KEY=**** (set)  MODEL=${OPENAI_MODEL:-gpt-4o}" ;;
        ollama)     echo "OLLAMA_URL=${OLLAMA_URL:-}  MODEL=${OLLAMA_MODEL:-}" ;;
        *)          echo "(backend: $LLM_BACKEND)" ;;
    esac
}

# Pre-compute console transport flag (avoid subshell boolean eval in output).
_console_enabled() {
    if $TELEGRAM_ENABLED; then echo "false"; else echo "true"; fi
}

review_config() {
    step_header "Review configuration"
    printf "\n  The following .env file will be written:\n\n"

    local llm_info console_en
    llm_info="$(_llm_preview)"
    console_en="$(_console_enabled)"

    printf "  ${D}─── LLM ──────────────────────────────────────────────────${NC}\n"
    printf "  BOT_DEFAULT_LLM_BACKEND=%s\n" "$LLM_BACKEND"
    printf "  %s\n" "$llm_info"
    printf "\n"
    printf "  ${D}─── Transport ────────────────────────────────────────────${NC}\n"
    printf "  BOT_TRANSPORTS_TELEGRAM_ENABLED=%s\n" "$TELEGRAM_ENABLED"
    printf "  BOT_TRANSPORTS_CONSOLE_ENABLED=%s\n"  "$console_en"
    printf "\n"
    printf "  ${D}─── Admin UI ─────────────────────────────────────────────${NC}\n"
    printf "  BOT_AGENT_NAME=%s\n"    "$AGENT_NAME"
    printf "  BOT_WEB_USERNAME=%s\n"  "$WEB_USER"
    printf "  BOT_WEB_PASSWORD=**** (set)\n"
    printf "  BOT_WEB_PORT=%s\n"      "$WEB_PORT"
    printf "\n"
    printf "  ${D}─── Language & timezone ──────────────────────────────────${NC}\n"
    printf "  BOT_TIMEZONE=%s\n" "$BOT_TIMEZONE"
    printf "  BOT_LANGUAGE=%s\n" "$BOT_LANGUAGE"
    printf "\n"
    printf "  ${D}─── Storage ──────────────────────────────────────────────${NC}\n"
    printf "  BOT_STORAGE_OBSIDIAN_ENABLED=%s\n" "$OBSIDIAN_ENABLED"
    $OBSIDIAN_ENABLED && printf "  OBSIDIAN_VAULT_PATH=%s\n" "$OBSIDIAN_PATH"
    printf "\n"

    if ! ask_yn "Continue and write .env?"; then
        echo
        die "Aborted by user."
    fi
}

check_existing_database() {
    local db="./data/bot.db"
    [[ -f "$db" ]] || return 0   # no database yet — nothing to do

    local size
    size=$(du -sh "$db" 2>/dev/null | cut -f1)
    echo
    warn "Existing database found: ${db} (${size})"
    printf "  ${D}It contains agents, conversation history, commands, and schedules${NC}\n"
    printf "  ${D}from a previous run. Old agent names and seeds will NOT be replaced${NC}\n"
    printf "  ${D}unless you drop the database and let it reseed from the new .env.${NC}\n\n"

    if ask_yn "Drop existing database and start fresh?" "n"; then
        rm -f "$db"
        ok "Database removed — will be recreated with the new configuration on first start."
    else
        ok "Keeping existing database. New .env settings apply only to runtime config."
        info "To reset later: rm ./data/bot.db  then  docker compose restart coach-bot"
    fi
}

write_env() {
    local env_file=".env"

    if [[ -f "$env_file" ]]; then
        warn ".env already exists."
        if ! ask_yn "Overwrite it?"; then
            die "Aborted — existing .env kept."
        fi
        cp "$env_file" "${env_file}.bak"
        info "Backup saved to .env.bak"
    fi

    local console_en
    console_en="$(_console_enabled)"

    {
        echo "# Coach-bot — generated by setup.sh on $(date)"
        echo ""
        echo "# ── LLM backend ──────────────────────────────────────────────"
        echo "BOT_DEFAULT_LLM_BACKEND=${LLM_BACKEND}"
        echo ""
        case "$LLM_BACKEND" in
            gemini)
                echo "BOT_LLM_GEMINI_ENABLED=true"
                printf 'GEMINI_API_KEY=%s\n' "${GEMINI_API_KEY}"
                ;;
            claude)
                echo "BOT_LLM_CLAUDE_ENABLED=true"
                printf 'ANTHROPIC_API_KEY=%s\n' "${ANTHROPIC_API_KEY}"
                ;;
            openai)
                echo "BOT_LLM_OPENAI_ENABLED=true"
                printf 'OPENAI_API_KEY=%s\n' "${OPENAI_API_KEY}"
                [[ -n "${OPENAI_MODEL:-}" ]]    && printf 'BOT_LLM_OPENAI_MODEL=%s\n'    "${OPENAI_MODEL}"
                [[ -n "${OPENAI_BASE_URL:-}" ]] && printf 'BOT_LLM_OPENAI_BASE_URL=%s\n' "${OPENAI_BASE_URL}"
                ;;
            ollama)
                echo "BOT_LLM_OLLAMA_ENABLED=true"
                printf 'BOT_LLM_OLLAMA_BASE_URL=%s\n' "${OLLAMA_URL}"
                printf 'BOT_LLM_OLLAMA_MODEL=%s\n'    "${OLLAMA_MODEL}"
                ;;
        esac
        echo ""
        echo "# ── Transports ────────────────────────────────────────────────"
        printf 'BOT_TRANSPORTS_CONSOLE_ENABLED=%s\n'  "${console_en}"
        printf 'BOT_TRANSPORTS_TELEGRAM_ENABLED=%s\n' "${TELEGRAM_ENABLED}"
        [[ -n "$TELEGRAM_TOKEN" ]] && printf 'TELEGRAM_BOT_TOKEN=%s\n' "${TELEGRAM_TOKEN}"
        echo ""
        echo "# ── Admin UI ──────────────────────────────────────────────────"
        printf 'BOT_AGENT_NAME="%s"\n'  "${AGENT_NAME}"
        printf 'BOT_WEB_USERNAME=%s\n' "${WEB_USER}"
        printf 'BOT_WEB_PASSWORD=%s\n' "${WEB_PASS}"
        printf 'BOT_WEB_PORT=%s\n'     "${WEB_PORT}"
        echo ""
        echo "# ── Language / timezone / translation ─────────────────────────"
        printf 'BOT_TIMEZONE=%s\n'  "${BOT_TIMEZONE}"
        printf 'BOT_LANGUAGE=%s\n' "${BOT_LANGUAGE}"
        [[ -n "${TRANSLATE_EMAIL:-}" ]] && printf 'BOT_TRANSLATE_API_KEY=%s\n' "${TRANSLATE_EMAIL}"
        echo ""
        echo "# ── Storage ───────────────────────────────────────────────────"
        printf 'BOT_STORAGE_OBSIDIAN_ENABLED=%s\n' "${OBSIDIAN_ENABLED}"
        # Quote the vault path — it may contain spaces or apostrophes.
        $OBSIDIAN_ENABLED && printf 'OBSIDIAN_VAULT_PATH="%s"\n' "${OBSIDIAN_PATH}"
    } > "$env_file"

    ok ".env written."
}

# ── Docker operations ──────────────────────────────────────────────────────────

build_and_start() {
    step_header "Building & starting coach-bot"
    info "This may take a few minutes on first run (Maven + Vaadin frontend)."
    echo

    $DOCKER_CMD build -q &
    spinner $! "Building Docker image …"

    $DOCKER_CMD up -d &
    spinner $! "Starting services …"

    # Wait for HTTP to be ready (max 60 s)
    local port="${WEB_PORT:-8080}"
    local waited=0
    printf "  ${C}→${NC}  Waiting for admin UI on port %s …" "$port"
    until curl -sf "http://localhost:${port}/login" -o /dev/null 2>/dev/null; do
        sleep 2
        (( waited += 2 ))
        if (( waited > 60 )); then
            echo
            warn "Admin UI did not respond within 60 s — the bot may still be starting."
            return
        fi
    done
    echo
    ok "Admin UI is up → http://localhost:${port}"
}

run_onboarding() {
    step_header "Onboarding — personalise your coach"
    printf "  ${D}The wizard will ask a few questions and generate a system prompt${NC}\n"
    printf "  ${D}tailored to your goals. You can re-run it any time with:${NC}\n"
    printf "  ${D}  docker compose run --rm coach-bot --mode=onboard${NC}\n\n"

    if ask_yn "Run the onboarding wizard now?"; then
        echo
        $DOCKER_CMD run --rm -it coach-bot --mode=onboard || true
    else
        info "Skipped. Run onboarding later with:"
        info "  $DOCKER_CMD run --rm coach-bot --mode=onboard"
    fi
}

setup_commands() {
    step_header "Commands — add slash-commands (optional)"
    printf "  ${D}Slash-commands like /quiz or /hint can be added now or later${NC}\n"
    printf "  ${D}via the admin UI at http://localhost:${WEB_PORT:-8080}${NC}\n\n"

    if ask_yn "Open the management console to add commands now?" "n"; then
        echo
        printf "  ${D}Type: command add default /quiz  \"Ask a random interview question\"${NC}\n"
        printf "  ${D}Then: exit${NC}\n\n"
        $DOCKER_CMD run --rm -it coach-bot --mode=manage || true
        $DOCKER_CMD restart coach-bot
        ok "Bot restarted with new commands."
    else
        info "Skipped. Add commands any time via the admin UI (Commands tab)."
    fi
}

# ── Summary ────────────────────────────────────────────────────────────────────

show_summary() {
    echo
    printf "${G}${B}"
    echo "  ╔═════════════════════════════════════════════════════╗"
    echo "  ║   🎉  Coach-bot is ready!                           ║"
    echo "  ╚═════════════════════════════════════════════════════╝"
    printf "${NC}\n"

    printf "  ${B}Admin UI${NC}       http://localhost:${WEB_PORT:-8080}\n"
    printf "  ${B}Credentials${NC}    %s / %s\n" "$WEB_USER" "$(printf '%*s' ${#WEB_PASS} '' | tr ' ' '*')"
    echo
    printf "  ${B}Useful commands:${NC}\n"
    printf "  ${D}%-46s${NC} %s\n" "$DOCKER_CMD logs -f coach-bot"        "live logs"
    printf "  ${D}%-46s${NC} %s\n" "$DOCKER_CMD restart coach-bot"         "restart"
    printf "  ${D}%-46s${NC} %s\n" "$DOCKER_CMD run --rm coach-bot --mode=onboard"  "re-run onboarding"
    printf "  ${D}%-46s${NC} %s\n" "$DOCKER_CMD run --rm coach-bot --mode=manage"   "manage agents/commands"
    echo
    if $TELEGRAM_ENABLED; then
        ok  "Telegram bot is active — add it to a chat and send /start"
    else
        info "Add Telegram later: set TELEGRAM_BOT_TOKEN and BOT_TRANSPORTS_TELEGRAM_ENABLED=true in .env"
    fi
    echo
}

# ── Trap ───────────────────────────────────────────────────────────────────────

trap 'echo; die "Interrupted."' INT TERM

# ── Main ───────────────────────────────────────────────────────────────────────

main() {
    clear
    printf "${C}${B}"
    echo ""
    echo "  ╔═══════════════════════════════════════════════════════╗"
    echo "  ║        🤖  Coach-bot Setup Wizard                     ║"
    echo "  ║    AI coaching bot — ready in about 5 minutes         ║"
    echo "  ╚═══════════════════════════════════════════════════════╝"
    printf "${NC}\n"

    # Must run from the repo root
    [[ -f "docker-compose.yml" ]] || die "Run this script from the coach-bot repository root."

    check_prerequisites
    step_llm
    step_telegram
    step_admin
    step_language
    step_obsidian
    review_config
    check_existing_database
    write_env
    build_and_start
    run_onboarding
    setup_commands
    show_summary
}

main "$@"
