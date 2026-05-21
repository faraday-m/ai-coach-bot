package dev.coachbot.core;

import dev.coachbot.cli.ManageCli;
import dev.coachbot.core.CommandRepository;
import dev.coachbot.llm.LlmBackend;
import dev.coachbot.llm.LlmBackendRegistry;
import dev.coachbot.onboarding.OnboardWizard;
import dev.coachbot.scheduler.ScheduleRepository;
import dev.coachbot.storage.StorageBackendRegistry;
import dev.coachbot.translation.TranslationService;
import dev.coachbot.transport.InboundMessage;
import dev.coachbot.transport.TransportPlugin;
import dev.coachbot.transport.TransportRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

/**
 * Central coordinator: routes inbound messages to the appropriate {@link GroupSession}.
 *
 * <p>Lifecycle (via {@link ApplicationRunner}, runs after full context initialisation):
 * <ol>
 *   <li>Validates all configured backends are registered.</li>
 *   <li>Creates and starts a {@link GroupSession} for each enabled agent.</li>
 *   <li>Starts all transports and registers itself as the inbound handler.</li>
 *   <li>Blocks until JVM shutdown signal.</li>
 * </ol>
 */
@Service
public class Orchestrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Orchestrator.class);

    private final AgentRepository agentRepository;
    private final CommandRepository commandRepository;
    private final ScheduleRepository scheduleRepository;
    private final LlmBackendRegistry llmRegistry;
    private final StorageBackendRegistry storageRegistry;
    private final TransportRegistry transportRegistry;
    private final HistoryStore historyStore;
    private final UserIdentityStore identityStore;
    private final TranslationService translationService;

    /**
     * When set, any agent in the DB that references an unregistered LLM backend
     * is automatically migrated to this one. Useful when switching LLMs without
     * editing the DB: {@code BOT_DEFAULT_LLM_BACKEND=gemini}.
     */
    private final String defaultLlmBackend;

    /** When true, a /start message from an unknown chat auto-registers all enabled agents. */
    private final boolean autoRegisterEnabled;

    private final Map<String, GroupSession> sessions = new ConcurrentHashMap<>();

    public Orchestrator(AgentRepository agentRepository,
                        CommandRepository commandRepository,
                        ScheduleRepository scheduleRepository,
                        LlmBackendRegistry llmRegistry,
                        StorageBackendRegistry storageRegistry,
                        TransportRegistry transportRegistry,
                        HistoryStore historyStore,
                        UserIdentityStore identityStore,
                        TranslationService translationService,
                        @Value("${BOT_DEFAULT_LLM_BACKEND:}") String defaultLlmBackend,
                        @Value("${bot.auto-register.enabled:true}") boolean autoRegisterEnabled) {
        this.agentRepository     = agentRepository;
        this.commandRepository   = commandRepository;
        this.scheduleRepository  = scheduleRepository;
        this.llmRegistry         = llmRegistry;
        this.storageRegistry     = storageRegistry;
        this.transportRegistry   = transportRegistry;
        this.historyStore        = historyStore;
        this.identityStore       = identityStore;
        this.translationService  = translationService;
        this.defaultLlmBackend   = defaultLlmBackend;
        this.autoRegisterEnabled = autoRegisterEnabled;
    }

    @Override
    public void run(ApplicationArguments args) throws InterruptedException {
        // ── Management CLI mode ────────────────────────────────────────────────
        List<String> mode = args.getOptionValues("mode");
        if (mode != null && mode.contains("manage")) {
            log.info("Starting in management mode — bot will not start");
            new ManageCli(agentRepository, commandRepository, scheduleRepository,
                    llmRegistry.available(), storageRegistry.available()).run();
            System.exit(0); // stop embedded Tomcat (added by Vaadin) from keeping the process alive
        }

        // ── Onboarding wizard mode ─────────────────────────────────────────────
        if (mode != null && mode.contains("onboard")) {
            log.info("Starting in onboarding mode — bot will not start");
            LlmBackend onboardLlm = resolveOnboardLlm();
            if (onboardLlm == null) { System.exit(1); return; }
            new OnboardWizard(agentRepository, onboardLlm, translationService).run();
            System.exit(0);
        }

        List<AgentConfig> agents = agentRepository.findAllEnabled();
        if (agents.isEmpty()) {
            log.warn("No enabled agents found — nothing to do");
            return;
        }

        agents = migrateStaleBackends(agents);
        validateBackends(agents);

        // Start a GroupSession for each agent
        for (AgentConfig agent : agents) {
            GroupSession session = new GroupSession(
                    agent,
                    llmRegistry.get(agent.llmBackendId()),
                    storageRegistry.get(agent.storageBackendId()),
                    transportRegistry,
                    historyStore,
                    identityStore,
                    translationService,
                    agentRepository,
                    commandRepository
            );
            session.start();
            sessions.put(agent.id(), session);
        }

        // Start all transports — pass this::handleMessage as the inbound callback
        for (TransportPlugin transport : transportRegistry.all()) {
            transport.start(this::handleMessage);
            log.info("Transport started: {}", transport.id());
        }

        log.info("Coach-bot ready. {} agents, {} transports.",
                sessions.size(), transportRegistry.available().size());

        // Block until shutdown (Ctrl+C / Docker stop)
        CountDownLatch shutdownLatch = new CountDownLatch(1);
        // addShutdownHook requires an UNSTARTED thread — use unstarted(), not start()
        Runtime.getRuntime().addShutdownHook(
                Thread.ofPlatform().name("shutdown-hook").unstarted(() -> {
                    log.info("Shutdown signal received — stopping sessions and transports");
                    sessions.values().forEach(GroupSession::stop);
                    transportRegistry.all().forEach(TransportPlugin::stop);
                    shutdownLatch.countDown();
                }));

        shutdownLatch.await();
    }

    // ── Hot-reload ─────────────────────────────────────────────────────────────

    /**
     * Reloads commands and the base system prompt for a running session.
     * Called from the web UI when the user saves commands or the system prompt.
     * No-op if the session is not running (e.g. agent is disabled).
     */
    public void reloadSession(String agentId) {
        GroupSession session = sessions.get(agentId);
        if (session != null) {
            session.reload();
        } else {
            log.debug("reloadSession: no running session for agent '{}'", agentId);
        }
    }

    // ── Message routing ────────────────────────────────────────────────────────

    private void handleMessage(InboundMessage msg) {
        List<AgentConfig> candidates = agentRepository.findByTransport(
                msg.transportId(), msg.chatId());

        if (candidates.isEmpty()) {
            if (autoRegisterEnabled && "/start".equalsIgnoreCase(msg.text().trim())) {
                handleAutoRegister(msg);
            } else {
                log.debug("No agent for transport={} chatId={} — ignoring (not /start or auto-register disabled)",
                        msg.transportId(), msg.chatId());
            }
            return;
        }

        for (AgentConfig agent : candidates) {
            String text = msg.text().trim();

            if (agent.requireTrigger()) {
                String trigger = agent.trigger();
                if (!text.toLowerCase().startsWith(trigger.toLowerCase())) {
                    continue; // message doesn't address this agent
                }
                text = text.substring(trigger.length()).trim();
                if (text.isEmpty()) continue; // just the trigger, no message body
            }

            GroupSession session = sessions.get(agent.id());
            if (session != null) {
                session.enqueue(msg.withText(text));
                log.debug("Routed message to agent '{}'", agent.id());
            }
        }
    }

    /**
     * Called when an unknown chat sends {@code /start}.
     * Registers all currently enabled agents for that (transport, chatId) pair
     * and sends a welcome message.
     */
    private void handleAutoRegister(InboundMessage msg) {
        List<AgentConfig> allAgents = agentRepository.findAllEnabled();
        if (allAgents.isEmpty()) {
            log.warn("Auto-register: /start received but no enabled agents found.");
            return;
        }

        for (AgentConfig agent : allAgents) {
            agentRepository.insertTransport(agent.id(), msg.transportId(), msg.chatId());
            log.info("Auto-registered agent '{}' for transport={} chatId={}",
                    agent.id(), msg.transportId(), msg.chatId());
        }

        String agentList = allAgents.stream()
                .map(a -> a.requireTrigger() ? a.trigger() + " — " + a.name() : a.name())
                .collect(java.util.stream.Collectors.joining("\n• ", "• ", ""));

        String welcome = "👋 Welcome! This chat is now connected to the coaching bot.\n\n" +
                "Available coaches:\n" + agentList + "\n\n" +
                "Send /onboard" + (allAgents.get(0).requireTrigger()
                        ? " (after the trigger, e.g. `" + allAgents.get(0).trigger() + " /onboard`)" : "") +
                " to get a personalised coaching experience!";

        transportRegistry.find(msg.transportId())
                .ifPresent(t -> t.send(msg.chatId(), welcome));
    }

    // ── Startup validation & migration ────────────────────────────────────────

    /**
     * If {@code BOT_DEFAULT_LLM_BACKEND} is set and an agent references an LLM backend
     * that is no longer registered (e.g. the user switched from Claude to Gemini),
     * automatically updates the agent in the DB and returns an updated list.
     *
     * <p>This lets you switch LLMs without touching the database:
     * <pre>
     *   BOT_LLM_GEMINI_ENABLED=true
     *   GEMINI_API_KEY=AIza...
     *   BOT_DEFAULT_LLM_BACKEND=gemini
     * </pre>
     */
    private List<AgentConfig> migrateStaleBackends(List<AgentConfig> agents) {
        if (!StringUtils.hasText(defaultLlmBackend)) return agents;

        if (!llmRegistry.has(defaultLlmBackend)) {
            log.warn("BOT_DEFAULT_LLM_BACKEND='{}' is set but not registered — ignoring. Available: {}",
                    defaultLlmBackend, llmRegistry.available());
            return agents;
        }

        return agents.stream().map(agent -> {
            if (!llmRegistry.has(agent.llmBackendId())) {
                log.warn("Agent '{}': LLM backend '{}' is not registered → migrating to '{}' " +
                                "(BOT_DEFAULT_LLM_BACKEND). DB record updated.",
                        agent.id(), agent.llmBackendId(), defaultLlmBackend);
                agentRepository.updateLlmBackend(agent.id(), defaultLlmBackend);
                return new AgentConfig(
                        agent.id(), agent.name(), agent.systemPrompt(),
                        defaultLlmBackend, agent.storageBackendId(),
                        agent.trigger(), agent.requireTrigger(), agent.enabled());
            }
            return agent;
        }).toList();
    }

    /**
     * Picks the LLM backend to use for onboarding prompt generation.
     * Uses {@code BOT_DEFAULT_LLM_BACKEND} if set, otherwise the first available backend.
     */
    private LlmBackend resolveOnboardLlm() {
        if (StringUtils.hasText(defaultLlmBackend) && llmRegistry.has(defaultLlmBackend)) {
            return llmRegistry.get(defaultLlmBackend);
        }
        if (!llmRegistry.available().isEmpty()) {
            String first = llmRegistry.available().iterator().next();
            log.info("Onboarding: BOT_DEFAULT_LLM_BACKEND not set — using first available backend: {}", first);
            return llmRegistry.get(first);
        }
        log.error("Onboarding: no LLM backends are registered. " +
                "Enable at least one (e.g. BOT_LLM_GEMINI_ENABLED=true).");
        return null;
    }

    private void validateBackends(List<AgentConfig> agents) {
        for (AgentConfig agent : agents) {
            if (!llmRegistry.has(agent.llmBackendId())) {
                throw new IllegalStateException(("""
                        Agent '%s' requires LLM backend '%s' but it's not registered.
                        Available: %s
                        To switch backends without editing the DB, set:
                          BOT_LLM_<BACKEND>_ENABLED=true  (e.g. BOT_LLM_GEMINI_ENABLED=true)
                          BOT_DEFAULT_LLM_BACKEND=<backend>  (e.g. BOT_DEFAULT_LLM_BACKEND=gemini)
                        """).formatted(agent.id(), agent.llmBackendId(), llmRegistry.available()));
            }
            if (!storageRegistry.has(agent.storageBackendId())) {
                throw new IllegalStateException(
                        "Agent '%s' requires storage backend '%s' but it's not registered. Available: %s"
                                .formatted(agent.id(), agent.storageBackendId(), storageRegistry.available()));
            }
        }
    }
}
