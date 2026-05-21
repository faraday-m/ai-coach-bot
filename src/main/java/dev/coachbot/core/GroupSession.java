package dev.coachbot.core;

import dev.coachbot.core.CommandRepository.AgentCommand;
import dev.coachbot.llm.ConversationMessage;
import dev.coachbot.llm.LlmBackend;
import dev.coachbot.llm.LlmRequest;
import dev.coachbot.llm.LlmResponse;
import dev.coachbot.onboarding.OnboardingFlow;
import dev.coachbot.storage.StorageBackend;
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
    private static final String META_PROMPT_PATH = "prompts/meta/generate-coach-prompt.md";

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

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
