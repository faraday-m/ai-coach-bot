package dev.coachbot.core;

import dev.coachbot.core.CommandRepository.AgentCommand;
import dev.coachbot.llm.ConversationMessage;
import dev.coachbot.llm.LlmBackend;
import dev.coachbot.llm.LlmRequest;
import dev.coachbot.llm.LlmResponse;
import dev.coachbot.onboarding.OnboardingFlow;
import dev.coachbot.storage.StorageBackend;
import dev.coachbot.storage.StorageException;
import dev.coachbot.translation.TranslationService;
import dev.coachbot.transport.InboundMessage;
import dev.coachbot.transport.TransportRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages one agent's conversation loop.
 *
 * <p>Each GroupSession runs in its own virtual thread, processing messages from
 * an inbound queue. Multiple GroupSessions (java-coach, english-coach, …) run
 * in parallel inside the same JVM.
 *
 * <p>History is loaded lazily from {@link HistoryStore} on the first message from each user
 * and kept in memory for the session lifetime. Each new exchange is persisted immediately,
 * so context survives JVM restarts.
 *
 * <h2>Onboarding</h2>
 * <p>When a user sends {@code /onboard} (after the trigger is stripped), the session starts
 * an {@link OnboardingFlow} for that user. All subsequent messages from that user are fed
 * to the flow until it completes or the user sends {@code /cancel}.
 * On completion the agent's system prompt is updated in the database (takes effect on restart
 * or when the session is reloaded).
 */
public class GroupSession {

    private static final Logger log = LoggerFactory.getLogger(GroupSession.class);
    private static final int MAX_HISTORY_TURNS = 20; // = 40 messages (user + assistant)
    private static final String META_PROMPT_PATH      = "prompts/meta/generate-coach-prompt.md";
    private static final String WIKI_PROMPT_PATH      = "prompts/meta/wiki-summary.md";

    private volatile AgentConfig agentConfig;
    private final LlmBackend llmBackend;
    private final StorageBackend storageBackend;
    private final TransportRegistry transportRegistry;
    private final HistoryStore historyStore;
    private final UserIdentityStore identityStore;
    private final TranslationService translationService;
    private final AgentRepository agentRepository;
    private final CommandRepository commandRepository;
    /** Lazily loaded meta-prompt template for onboarding. */
    private volatile String metaPromptTemplate;
    /** Lazily loaded meta-prompt template for /wiki summarisation. */
    private volatile String wikiPromptTemplate;
    /** Commands for this agent, loaded once at session start. */
    private volatile List<AgentCommand> commands = List.of();

    private final LinkedBlockingQueue<InboundMessage> queue = new LinkedBlockingQueue<>();
    /** In-memory cache keyed by canonical user ID — populated lazily from DB. */
    private final Map<String, List<ConversationMessage>> userHistories = new ConcurrentHashMap<>();
    /** Active onboarding flows keyed by canonical user ID. */
    private final Map<String, OnboardingFlow> onboardingFlows = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread workerThread;

    public GroupSession(AgentConfig agentConfig,
                        LlmBackend llmBackend,
                        StorageBackend storageBackend,
                        TransportRegistry transportRegistry,
                        HistoryStore historyStore,
                        UserIdentityStore identityStore,
                        TranslationService translationService,
                        AgentRepository agentRepository,
                        CommandRepository commandRepository) {
        this.agentConfig        = agentConfig;
        this.llmBackend         = llmBackend;
        this.storageBackend     = storageBackend;
        this.transportRegistry  = transportRegistry;
        this.historyStore       = historyStore;
        this.identityStore      = identityStore;
        this.translationService = translationService;
        this.agentRepository    = agentRepository;
        this.commandRepository  = commandRepository;
    }

    public void start() {
        commands = commandRepository.findEnabledByAgent(agentConfig.id());
        running.set(true);
        workerThread = Thread.ofVirtual()
                .name("session-" + agentConfig.id())
                .start(this::processLoop);
        log.info("Session started: agent='{}' llm='{}' storage='{}' commands={}",
                agentConfig.id(), agentConfig.llmBackendId(), agentConfig.storageBackendId(),
                commands.stream().map(AgentCommand::trigger).toList());
    }

    public void stop() {
        running.set(false);
        if (workerThread != null) workerThread.interrupt();
    }

    /**
     * Hot-reloads commands and the base system prompt from the database.
     * Takes effect on the next incoming message — no restart required.
     * Per-user history and onboarding overrides are preserved.
     */
    public void reload() {
        commands = commandRepository.findEnabledByAgent(agentConfig.id());
        agentRepository.findById(agentConfig.id()).ifPresent(fresh -> agentConfig = fresh);
        log.info("[{}] Hot-reloaded: {} command(s)", agentConfig.id(), commands.size());
    }

    public void enqueue(InboundMessage message) {
        queue.offer(message);
    }

    public AgentConfig agentConfig() {
        return agentConfig;
    }

    // ── Processing loop ────────────────────────────────────────────────────────

    private void processLoop() {
        while (running.get()) {
            try {
                InboundMessage msg = queue.poll(5, TimeUnit.SECONDS);
                if (msg != null) processMessage(msg);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[{}] Unexpected error in process loop", agentConfig.id(), e);
            }
        }
        log.info("Session stopped: {}", agentConfig.id());
    }

    private void processMessage(InboundMessage msg) {
        // Resolve transport-specific sender to a stable canonical ID.
        // Two transport keys (e.g. "telegram:123" and "jabber:me@host") that have been
        // /link-ed share the same canonical ID → same history with this agent.
        String canonicalId = identityStore.resolve(msg.transportId(), msg.senderId());

        log.info("[{}] ← {}:{} \"{}\"",
                agentConfig.id(), msg.transportId(), msg.senderId(),
                truncate(msg.text(), 120));

        String text = msg.text().trim();

        // ── /cancel — abort any active flow ───────────────────────────────────
        if (text.equalsIgnoreCase("/cancel")) {
            if (onboardingFlows.remove(canonicalId) != null) {
                reply(msg, "Onboarding cancelled. Type /onboard to start again.");
            } else {
                reply(msg, "Nothing to cancel.");
            }
            return;
        }

        // ── Active onboarding flow — feed input to FSM ────────────────────────
        if (onboardingFlows.containsKey(canonicalId)) {
            handleOnboardingAnswer(msg, canonicalId, text);
            return;
        }

        // ── /onboard — start a new flow ───────────────────────────────────────
        if (text.equalsIgnoreCase("/onboard")) {
            startOnboarding(msg, canonicalId);
            return;
        }

        // ── /link — cross-transport identity linking ──────────────────────────
        if (text.startsWith("/link ")) {
            handleLink(msg, canonicalId, text.substring(6).trim());
            return;
        }

        // ── /agent list — show agents in this chat ───────────────────────────
        if (text.equalsIgnoreCase("/agent list")) {
            handleAgentList(msg);
            return;
        }

        // ── /wiki — save to storage ───────────────────────────────────────────
        if (text.startsWith("/wiki")) {
            handleWiki(msg, canonicalId, text.substring(5).trim());
            return;
        }

        // ── Normal LLM conversation ───────────────────────────────────────────
        handleLlmMessage(msg, canonicalId, text);
    }

    // ── /link ──────────────────────────────────────────────────────────────────

    private void handleLink(InboundMessage msg, String canonicalId, String targetTransportKey) {
        int colonIdx = targetTransportKey.indexOf(':');
        if (targetTransportKey.isBlank() || colonIdx <= 0) {
            reply(msg, "Usage: /link <transport>:<userId>  e.g. /link console:me");
            return;
        }
        try {
            String targetTransport = targetTransportKey.substring(0, colonIdx);
            String targetSender    = targetTransportKey.substring(colonIdx + 1);
            String targetCanonical = identityStore.resolve(targetTransport, targetSender);
            String fromKey         = msg.transportId() + ":" + msg.senderId();
            identityStore.link(fromKey, targetCanonical);
            reply(msg, "✅ Linked. Your " + msg.transportId() + " and " + targetTransport
                    + " accounts now share conversation history.");
        } catch (UnsupportedOperationException e) {
            reply(msg, "⚠️ Identity linking is not supported in this configuration.");
        } catch (Exception e) {
            log.error("[{}] Error during /link", agentConfig.id(), e);
            reply(msg, "⚠️ Link failed: " + e.getMessage());
        }
    }

    // ── /agent list ────────────────────────────────────────────────────────────

    private void handleAgentList(InboundMessage msg) {
        List<AgentConfig> agents = agentRepository.findByTransport(msg.transportId(), msg.chatId());
        if (agents.isEmpty()) {
            reply(msg, "No agents are assigned to this chat.");
            return;
        }
        var sb = new StringBuilder("Agents in this chat:\n");
        for (AgentConfig a : agents) {
            sb.append("• ").append(a.name());
            if (a.requireTrigger()) sb.append(" (trigger: ").append(a.trigger()).append(")");
            sb.append("\n");
        }
        reply(msg, sb.toString());
    }

    // ── /wiki ──────────────────────────────────────────────────────────────────

    /**
     * Agentic wiki command — distils conversation into one or more Markdown notes.
     *
     * <pre>
     * /wiki path/to/note                    — summarise messages since last /wiki
     * /wiki path/to/note user instruction   — same, but follow the user's directive
     * </pre>
     *
     * <p>The LLM receives only the messages added since the previous {@code /wiki} call
     * (tracked via {@link #wikiCheckpoints}), so repeated calls naturally segment the
     * conversation without re-processing old material.
     *
     * <p>The LLM may produce multiple {@code <wiki_file path="...">...</wiki_file>} blocks
     * when the user asks for several articles or when the conversation spans distinct topics.
     * Each block is saved as a separate file in the agent's storage backend.
     */
    private void handleWiki(InboundMessage msg, String canonicalId, String args) {
        if (args.isBlank()) {
            reply(msg, """
                    Usage:
                    • `/wiki <path>` — summarises our conversation since the last /wiki
                    • `/wiki <path> <instruction>` — same, but follow your directive

                    Examples:
                      /wiki notes/virtual-threads
                      /wiki notes/java Напиши подробную статью про сборщик мусора
                      /wiki notes/ Сохрани GC и virtual threads как отдельные статьи""");
            return;
        }

        List<ConversationMessage> history = userHistories.get(canonicalId);
        if (history == null || history.isEmpty()) {
            reply(msg, "⚠️ No conversation to summarise yet. Chat with me first, then use /wiki.");
            return;
        }

        // ── Extract only messages since last /wiki ────────────────────────────
        int checkpoint = wikiCheckpoints.getOrDefault(canonicalId, 0);
        // Guard against history being trimmed between two /wiki calls
        int from = Math.min(checkpoint, history.size());
        List<ConversationMessage> newMessages = history.subList(from, history.size());

        if (newMessages.isEmpty()) {
            reply(msg, "ℹ️ Nothing new since the last /wiki. Start a new topic and then call /wiki again.");
            return;
        }

        // ── Parse path and optional instruction ───────────────────────────────
        int space = args.indexOf(' ');
        String pathHint   = space == -1 ? args                        : args.substring(0, space);
        String instruction = space == -1 ? null                       : args.substring(space + 1).trim();

        log.info("[{}] /wiki: summarising {} message(s) since checkpoint {} → '{}'{}",
                agentConfig.id(), newMessages.size(), checkpoint, pathHint,
                instruction != null ? " (instruction: \"" + truncate(instruction, 60) + "\")" : "");

        // Signal that we're working
        transportRegistry.find(msg.transportId()).ifPresent(t -> t.sendTyping(msg.chatId()));

        try {
            String llmResponse = callWikiAgent(newMessages, pathHint, instruction);
            List<WikiFile> files = parseWikiFiles(llmResponse, pathHint);

            if (files.isEmpty()) {
                log.warn("[{}] /wiki: LLM returned no <wiki_file> blocks — raw response: {}",
                        agentConfig.id(), truncate(llmResponse, 200));
                reply(msg, "⚠️ The LLM didn't produce a recognisable file block. Try again or rephrase your instruction.");
                return;
            }

            // Save each file and collect results
            var saved   = new ArrayList<String>();
            var failed  = new ArrayList<String>();
            for (WikiFile wf : files) {
                try {
                    storageBackend.write(wf.path(), wf.content());
                    log.info("[{}] /wiki: saved {} chars → '{}'", agentConfig.id(), wf.content().length(), wf.path());
                    saved.add("`" + wf.path() + "`");
                } catch (StorageException e) {
                    log.error("[{}] /wiki: failed to write '{}'", agentConfig.id(), wf.path(), e);
                    failed.add("`" + wf.path() + "` (" + e.getMessage() + ")");
                }
            }

            // Update checkpoint so next /wiki only processes new material
            wikiCheckpoints.put(canonicalId, history.size());

            // Report back
            var sb = new StringBuilder();
            if (!saved.isEmpty())  sb.append("✅ Saved: ").append(String.join(", ", saved));
            if (!failed.isEmpty()) sb.append("\n⚠️ Failed: ").append(String.join(", ", failed));
            reply(msg, sb.toString());

        } catch (Exception e) {
            log.error("[{}] /wiki: LLM call failed", agentConfig.id(), e);
            reply(msg, "⚠️ Could not generate wiki note: " + e.getMessage());
        }
    }

    /**
     * Sends the conversation slice to the LLM with the wiki agent meta-prompt.
     * Returns the raw LLM output (expected to contain {@code <wiki_file>} blocks).
     */
    private String callWikiAgent(List<ConversationMessage> messages, String pathHint, String instruction) {
        String wikiPrompt = loadWikiPrompt();

        // Format conversation slice as a readable dialogue
        var dialogue = new StringBuilder();
        for (ConversationMessage m : messages) {
            String speaker = m.role() == ConversationMessage.Role.USER ? "User" : "Assistant";
            dialogue.append(speaker).append(": ").append(m.content()).append("\n\n");
        }

        var userMessage = new StringBuilder();
        userMessage.append("Conversation:\n\n---\n")
                   .append(dialogue.toString().stripTrailing())
                   .append("\n---\n\n")
                   .append("Suggested path: `").append(pathHint).append("`");
        if (instruction != null && !instruction.isBlank()) {
            userMessage.append("\n\nUser instructions: ").append(instruction);
        }

        LlmRequest request = new LlmRequest(wikiPrompt, List.of(), userMessage.toString(), "wiki", agentConfig.id());
        return llmBackend.complete(request).text();
    }

    /**
     * Parses {@code <wiki_file path="...">...</wiki_file>} blocks from the LLM response.
     * Falls back to treating the entire response as a single file at {@code pathHint} if no
     * blocks are found — improves robustness with models that ignore the format instruction.
     */
    private List<WikiFile> parseWikiFiles(String llmResponse, String pathHint) {
        var files = new ArrayList<WikiFile>();
        Matcher m = WIKI_FILE_PATTERN.matcher(llmResponse);
        while (m.find()) {
            String path    = m.group(1).trim();
            String content = m.group(2).trim();
            if (!path.contains(".")) path = path + ".md";
            files.add(new WikiFile(path, content));
        }
        if (files.isEmpty() && !llmResponse.isBlank()) {
            // Fallback: no markers found — save entire response to the suggested path
            String path = pathHint.contains(".") ? pathHint : pathHint + ".md";
            files.add(new WikiFile(path, llmResponse.trim()));
        }
        return files;
    }

    private record WikiFile(String path, String content) {}

    // ── Onboarding ─────────────────────────────────────────────────────────────

    private void startOnboarding(InboundMessage msg, String canonicalId) {
        // Translate questions to the configured language (non-blocking — runs on session thread)
        OnboardingFlow flow;
        String warningPrefix = "";

        if (translationService.isTranslationEnabled()) {
            var result = translationService.translateAll(OnboardingFlow.defaultQuestions());
            flow = new OnboardingFlow(result.texts());
            if (result.translationFailed()) {
                warningPrefix = "⚠️ Translation service unavailable — questions shown in English.\n\n";
            }
        } else {
            flow = new OnboardingFlow();
        }

        onboardingFlows.put(canonicalId, flow);
        String intro = warningPrefix +
                "Let's personalise your coaching experience! " +
                "I'll ask you " + flow.totalSteps() + " quick questions. " +
                "Send /cancel at any time to stop.\n\n" +
                "[1/" + flow.totalSteps() + "] " + flow.start();
        reply(msg, intro);
    }

    private void handleOnboardingAnswer(InboundMessage msg, String canonicalId, String text) {
        OnboardingFlow flow = onboardingFlows.get(canonicalId);
        if (flow == null) return; // race condition safety

        transportRegistry.find(msg.transportId()).ifPresent(t -> t.sendTyping(msg.chatId()));

        try {
            OnboardingFlow.StepResult result = flow.answer(text, llmBackend, loadMetaPrompt());

            switch (result) {
                case OnboardingFlow.StepResult.NextQuestion nq -> {
                    int step = flow.progress() + 1;
                    reply(msg, "[" + step + "/" + flow.totalSteps() + "] " + nq.question());
                }
                case OnboardingFlow.StepResult.Done done -> {
                    onboardingFlows.remove(canonicalId);
                    String generated = done.generatedPrompt();
                    if (generated == null || generated.isBlank()) {
                        reply(msg, "⚠ Could not generate a prompt — the LLM returned an empty response. " +
                                "Try /onboard again.");
                        return;
                    }
                    // Persist new prompt to DB (survives restarts)
                    agentRepository.updateSystemPrompt(agentConfig.id(), generated);
                    log.info("[{}] Onboarding complete for {} — prompt ({} chars) saved to DB.",
                            agentConfig.id(), canonicalId, generated.length());

                    // Apply immediately for this session
                    userSystemPromptOverrides.put(canonicalId, generated);

                    // Clear old conversation history so the new coaching style starts clean
                    userHistories.remove(canonicalId);
                    historyStore.trim(agentConfig.id(), canonicalId, 0);

                    reply(msg, "✅ Onboarding complete! Your personalised coaching style is ready.\n\n" +
                            "Let's get started! What would you like to work on?");
                }
            }
        } catch (Exception e) {
            log.error("[{}] Onboarding error for {}", agentConfig.id(), canonicalId, e);
            onboardingFlows.remove(canonicalId);
            reply(msg, "⚠ Error during onboarding: " + e.getMessage() + ". Please try /onboard again.");
        }
    }

    /** Per-user system prompt overrides (active for this session only). */
    private final Map<String, String> userSystemPromptOverrides = new ConcurrentHashMap<>();

    /**
     * Tracks history size at the time of the last /wiki call per user.
     * Used to extract only "new since last save" messages for the next /wiki.
     * Stored as list size (not an index), so it's naturally bounded by MAX_HISTORY_TURNS.
     * If the list is trimmed between two /wiki calls, we fall back to all available history.
     */
    private final Map<String, Integer> wikiCheckpoints = new ConcurrentHashMap<>();

    /** Pattern that matches LLM-generated wiki file blocks: {@code <wiki_file path="...">...</wiki_file>}. */
    private static final Pattern WIKI_FILE_PATTERN =
            Pattern.compile("<wiki_file\\s+path=\"([^\"]+)\">(.*?)</wiki_file>", Pattern.DOTALL);


    // ── Normal LLM conversation ────────────────────────────────────────────────

    private void handleLlmMessage(InboundMessage msg, String canonicalId, String text) {
        // Lazy-load history from DB on first message from this canonical user this session
        List<ConversationMessage> history = userHistories.computeIfAbsent(
                canonicalId,
                id -> historyStore.load(agentConfig.id(), id, MAX_HISTORY_TURNS * 2));

        String systemPrompt = buildEffectiveSystemPrompt(
                userSystemPromptOverrides.getOrDefault(canonicalId, agentConfig.systemPrompt()));

        // Commands are stateless by design: send empty history so the LLM responds
        // fresh from the system prompt + command description only.
        // The exchange is still persisted below so follow-up messages have context.
        List<ConversationMessage> historyForLlm =
                isAgentCommand(text) ? List.of() : List.copyOf(history);

        LlmRequest request = new LlmRequest(
                systemPrompt,
                historyForLlm,
                text,
                canonicalId,
                agentConfig.id()
        );

        try {
            // Signal typing while LLM is running
            transportRegistry.find(msg.transportId())
                    .ifPresent(t -> t.sendTyping(msg.chatId()));

            LlmResponse response = llmBackend.complete(request);

            // Persist both messages before updating in-memory (crash-safe order)
            historyStore.append(agentConfig.id(), canonicalId, ConversationMessage.user(text));
            historyStore.append(agentConfig.id(), canonicalId, ConversationMessage.assistant(response.text()));

            // Update in-memory history
            history.add(ConversationMessage.user(text));
            history.add(ConversationMessage.assistant(response.text()));

            // Trim both in-memory and DB to MAX_HISTORY_TURNS
            if (history.size() > MAX_HISTORY_TURNS * 2) {
                history.subList(0, history.size() - MAX_HISTORY_TURNS * 2).clear();
                historyStore.trim(agentConfig.id(), canonicalId, MAX_HISTORY_TURNS * 2);
            }

            // Send response back
            transportRegistry.get(msg.transportId()).send(msg.chatId(), response.text());
            log.info("[{}] → {}:{} ({} chars)",
                    agentConfig.id(), msg.transportId(), msg.senderId(), response.text().length());

        } catch (Exception e) {
            log.error("[{}] Error completing LLM request", agentConfig.id(), e);
            tryReplyWithError(msg, e);
        }
    }

    // ── System prompt assembly ─────────────────────────────────────────────────

    /**
     * Returns the base system prompt with an optional commands section appended.
     * The commands section tells the LLM what to do when the user sends a slash-command.
     */
    private String buildEffectiveSystemPrompt(String base) {
        if (commands.isEmpty()) return base;
        StringBuilder sb = new StringBuilder(base.stripTrailing());
        sb.append("\n\n## Available commands\n");
        sb.append("When the user sends one of these commands, treat it as a self-contained request: ");
        sb.append("ignore the previous conversation history and respond based solely on the command description and your expertise.\n");
        for (AgentCommand cmd : commands) {
            sb.append("- `").append(cmd.trigger()).append("` — ").append(cmd.description()).append("\n");
        }
        return sb.toString();
    }

    private boolean isAgentCommand(String text) {
        if (!text.startsWith("/")) return false;
        String lower = text.toLowerCase();
        return commands.stream().anyMatch(cmd -> {
            String t = cmd.trigger().toLowerCase();
            return lower.equals(t) || lower.startsWith(t + " ");
        });
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void reply(InboundMessage msg, String text) {
        try {
            transportRegistry.get(msg.transportId()).send(msg.chatId(), text);
        } catch (Exception e) {
            log.error("[{}] Failed to send reply", agentConfig.id(), e);
        }
    }

    private void tryReplyWithError(InboundMessage msg, Exception e) {
        reply(msg, "⚠️ " + e.getMessage());
    }

    private String loadMetaPrompt() {
        if (metaPromptTemplate != null) return metaPromptTemplate;
        try {
            var resource = new ClassPathResource(META_PROMPT_PATH);
            metaPromptTemplate = resource.getContentAsString(StandardCharsets.UTF_8);
            return metaPromptTemplate;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load meta-prompt from classpath: " + META_PROMPT_PATH, e);
        }
    }

    private String loadWikiPrompt() {
        if (wikiPromptTemplate != null) return wikiPromptTemplate;
        try {
            var resource = new ClassPathResource(WIKI_PROMPT_PATH);
            wikiPromptTemplate = resource.getContentAsString(StandardCharsets.UTF_8);
            return wikiPromptTemplate;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load wiki meta-prompt from classpath: " + WIKI_PROMPT_PATH, e);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
