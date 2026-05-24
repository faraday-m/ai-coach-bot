package dev.coachbot.scheduler;

import dev.coachbot.core.AgentConfig;
import dev.coachbot.core.AgentRepository;
import dev.coachbot.core.CommandRepository;
import dev.coachbot.core.UserIdentityStore;
import dev.coachbot.llm.LlmBackend;
import dev.coachbot.llm.LlmBackendRegistry;
import dev.coachbot.llm.LlmRequest;
import dev.coachbot.storage.StorageBackend;
import dev.coachbot.storage.StorageBackendRegistry;
import dev.coachbot.transport.TransportPlugin;
import dev.coachbot.transport.TransportRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Registers and fires per-agent cron schedules loaded from the {@code agent_schedules} table.
 *
 * <p>Supports live add/cancel without restart — call {@link #register} and {@link #cancel}
 * from the web UI or management CLI after mutating the database.
 *
 * <p>Cron syntax: standard 5-part Unix cron ({@code "0 9 * * MON-FRI"}).
 * Spring's {@link CronExpression} is used for parsing and next-fire calculation.
 */
@Service
@org.springframework.context.annotation.DependsOn("schemaMigration")
public class AgentScheduler {

    private static final Logger log = LoggerFactory.getLogger(AgentScheduler.class);
    private static final String SR_PROMPT_PATH        = "prompts/meta/spaced-review.md";
    private static final String BOOTSTRAP_PROMPT_PATH = "prompts/meta/memory-bootstrap.md";

    private final ScheduleRepository scheduleRepo;
    private final AgentRepository agentRepo;
    private final CommandRepository commandRepo;
    private final UserIdentityStore identityStore;
    private final LlmBackendRegistry llmRegistry;
    private final TransportRegistry transportRegistry;
    private final StorageBackendRegistry storageRegistry;
    private final ZoneId timezone;

    /** Lazily loaded spaced-review meta-prompt template. */
    private volatile String srPromptTemplate;
    /** Lazily loaded memory-bootstrap meta-prompt template. */
    private volatile String bootstrapPromptTemplate;

    private final ScheduledExecutorService executor =
            Executors.newScheduledThreadPool(4, Thread.ofVirtual().factory());

    /** Active futures keyed by schedule ID — used for cancellation. */
    private final ConcurrentHashMap<Long, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    public AgentScheduler(ScheduleRepository scheduleRepo,
                          AgentRepository agentRepo,
                          CommandRepository commandRepo,
                          UserIdentityStore identityStore,
                          LlmBackendRegistry llmRegistry,
                          TransportRegistry transportRegistry,
                          StorageBackendRegistry storageRegistry,
                          @org.springframework.beans.factory.annotation.Value("${bot.timezone:UTC}") String timezone) {
        this.scheduleRepo      = scheduleRepo;
        this.agentRepo         = agentRepo;
        this.commandRepo       = commandRepo;
        this.identityStore     = identityStore;
        this.llmRegistry       = llmRegistry;
        this.transportRegistry = transportRegistry;
        this.storageRegistry   = storageRegistry;
        this.timezone          = ZoneId.of(timezone);
    }

    @PostConstruct
    public void registerAll() {
        List<ScheduleRepository.AgentSchedule> schedules = scheduleRepo.findAllEnabled();
        if (schedules.isEmpty()) {
            log.info("No active schedules found.");
        } else {
            for (ScheduleRepository.AgentSchedule s : schedules) {
                register(s);
            }
            log.info("Registered {} agent schedule(s).", schedules.size());
        }

        // Bootstrap memory at startup for any user who doesn't have it yet.
        // Runs in the background so it doesn't block Spring context startup.
        List<ScheduleRepository.AgentSchedule> srSchedules = schedules.stream()
                .filter(s -> "spaced_review".equals(s.scheduleType()))
                .toList();
        if (!srSchedules.isEmpty()) {
            executor.execute(() -> bootstrapExistingUsers(srSchedules));
        }
    }

    /**
     * Scans all users known to agents that have a {@code spaced_review} schedule and
     * bootstraps a memory document for any user who doesn't have one yet.
     *
     * <p>Called once at startup from {@link #registerAll()} — runs in a background thread
     * so the bot is fully ready to handle messages while bootstrapping proceeds.
     */
    private void bootstrapExistingUsers(List<ScheduleRepository.AgentSchedule> srSchedules) {
        for (ScheduleRepository.AgentSchedule schedule : srSchedules) {
            Optional<AgentConfig> agentOpt = agentRepo.findById(schedule.agentId());
            if (agentOpt.isEmpty() || !agentOpt.get().enabled()) continue;
            AgentConfig agent = agentOpt.get();
            if (!llmRegistry.has(agent.llmBackendId())) continue;
            LlmBackend llm = llmRegistry.get(agent.llmBackendId());
            Optional<StorageBackend> storageOpt = storageRegistry.find(agent.storageBackendId());
            if (storageOpt.isEmpty()) continue;
            StorageBackend storage = storageOpt.get();

            List<String> users = agentRepo.findUsersByAgent(agent.id());
            for (String canonUserId : users) {
                try {
                    String path = memoryPath(agent.id(), canonUserId);
                    Optional<String> existing = storage.read(path);
                    if (existing.isEmpty()) {
                        log.info("Startup bootstrap: user '{}' / agent '{}' has no memory — bootstrapping.",
                                canonUserId, agent.id());
                        bootstrapMemory(schedule, agent, llm, storage, path);
                    }
                } catch (Exception e) {
                    log.warn("Startup bootstrap failed for user '{}' / agent '{}'.",
                            canonUserId, agent.id(), e);
                }
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Registers (or re-registers) a schedule. Safe to call at runtime — if a future
     * is already running for this ID it is cancelled first.
     */
    public void register(ScheduleRepository.AgentSchedule schedule) {
        // Cancel any existing future for this ID before registering a new one
        ScheduledFuture<?> old = futures.remove(schedule.id());
        if (old != null) old.cancel(false);

        CronExpression cron;
        try {
            cron = CronExpression.parse(toSpringCron(schedule.cron()));
        } catch (IllegalArgumentException e) {
            log.warn("Schedule #{} agent='{}': invalid cron '{}' — skipping. ({})",
                    schedule.id(), schedule.agentId(), schedule.cron(), e.getMessage());
            return;
        }

        ZonedDateTime next = cron.next(ZonedDateTime.now(timezone));
        scheduleNext(schedule, cron);
        log.info("Schedule #{} registered: agent='{}' cron='{}' — next fire at {}",
                schedule.id(), schedule.agentId(), schedule.cron(), next);
    }

    /** Cancels a running schedule. No-op if the schedule is not currently registered. */
    public void cancel(long scheduleId) {
        ScheduledFuture<?> future = futures.remove(scheduleId);
        if (future != null) {
            future.cancel(false);
            log.info("Schedule #{} cancelled.", scheduleId);
        }
    }

    // ── Scheduling loop ────────────────────────────────────────────────────────

    private void scheduleNext(ScheduleRepository.AgentSchedule schedule, CronExpression cron) {
        ZonedDateTime now  = ZonedDateTime.now(timezone);
        ZonedDateTime next = cron.next(now);
        if (next == null) {
            log.warn("Schedule #{}: cron '{}' has no future firings — not scheduling.",
                    schedule.id(), schedule.cron());
            return;
        }
        long delayMs = Duration.between(now, next).toMillis();
        ScheduledFuture<?> future = executor.schedule(() -> {
            // If cancel() was called between scheduling and firing, skip.
            if (!futures.containsKey(schedule.id())) return;
            try {
                if ("spaced_review".equals(schedule.scheduleType())) {
                    fireSpacedReview(schedule);
                } else {
                    fire(schedule);
                }
            } catch (Exception e) {
                log.error("Schedule #{} fire error", schedule.id(), e);
            } finally {
                // Re-schedule only if still active
                if (futures.containsKey(schedule.id())) {
                    scheduleNext(schedule, cron);
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS);
        futures.put(schedule.id(), future);
    }

    // ── Fire ───────────────────────────────────────────────────────────────────

    private void fire(ScheduleRepository.AgentSchedule schedule) {
        Optional<AgentConfig> agentOpt = agentRepo.findById(schedule.agentId());
        if (agentOpt.isEmpty() || !agentOpt.get().enabled()) {
            log.info("Schedule #{}: agent '{}' not found or disabled — skipping.",
                    schedule.id(), schedule.agentId());
            return;
        }
        AgentConfig agent = agentOpt.get();

        if (!llmRegistry.has(agent.llmBackendId())) {
            log.warn("Schedule #{}: LLM backend '{}' not available — skipping.",
                    schedule.id(), agent.llmBackendId());
            return;
        }
        LlmBackend llm = llmRegistry.get(agent.llmBackendId());

        List<AgentRepository.TransportBinding> bindings = agentRepo.findTransports(agent.id());
        if (bindings.isEmpty()) {
            log.warn("Schedule #{}: agent '{}' has no transport bindings — skipping.",
                    schedule.id(), agent.id());
            return;
        }

        log.info("Schedule #{} firing: agent='{}' prompt='{}'",
                schedule.id(), agent.id(), truncate(schedule.prompt(), 80));

        LlmRequest request = LlmRequest.of(
                agent.systemPrompt(), List.of(), schedule.prompt(), "scheduler", agent.id());
        String response;
        try {
            response = llm.complete(request).text();
        } catch (Exception e) {
            log.error("Schedule #{}: LLM call failed", schedule.id(), e);
            return;
        }

        log.info("Schedule #{}: broadcasting {} chars to {} binding(s)",
                schedule.id(), response.length(), bindings.size());

        for (AgentRepository.TransportBinding binding : bindings) {
            Optional<TransportPlugin> transport = transportRegistry.find(binding.transportId());
            if (transport.isEmpty()) {
                log.warn("Schedule #{}: transport '{}' not registered — skipping chatId={}.",
                        schedule.id(), binding.transportId(), binding.chatId());
                continue;
            }
            try {
                transport.get().send(binding.chatId(), response);
                log.info("Schedule #{}: sent to {}:{}", schedule.id(),
                        binding.transportId(), binding.chatId());
            } catch (Exception e) {
                log.error("Schedule #{}: send failed to {}:{}",
                        schedule.id(), binding.transportId(), binding.chatId(), e);
            }
        }

        // ── Save to storage if save_path is configured ─────────────────────
        if (schedule.savePath() != null && !schedule.savePath().isBlank()) {
            saveToStorage(schedule, agent, response);
        }
    }

    private void saveToStorage(ScheduleRepository.AgentSchedule schedule, AgentConfig agent, String response) {
        Optional<StorageBackend> storageOpt = storageRegistry.find(agent.storageBackendId());
        if (storageOpt.isEmpty()) {
            log.warn("Schedule #{}: storage backend '{}' not available — skipping save.",
                    schedule.id(), agent.storageBackendId());
            return;
        }
        String path = resolveSavePath(schedule.savePath());
        // Add .md extension if no extension present
        if (!path.contains(".")) path = path + ".md";
        try {
            String timestamp = ZonedDateTime.now(timezone)
                    .format(DateTimeFormatter.ofPattern("HH:mm"));
            storageOpt.get().append(path, "\n## " + timestamp + "\n\n" + response + "\n");
            log.info("Schedule #{}: response appended to '{}'", schedule.id(), path);
        } catch (Exception e) {
            log.warn("Schedule #{}: failed to save response to '{}'", schedule.id(), path, e);
        }
    }

    /** Replaces {@code {date}} with today's date in YYYY-MM-DD format. */
    private String resolveSavePath(String template) {
        String date = LocalDate.now(timezone).format(DateTimeFormatter.ISO_LOCAL_DATE);
        return template.replace("{date}", date);
    }

    // ── Spaced-review fire ─────────────────────────────────────────────────────

    /**
     * Fires a {@code spaced_review} schedule.
     *
     * <p>For every user who has a memory document for this agent, calls the LLM with
     * the SR meta-prompt and the user's memory. If the LLM decides a topic is due it
     * returns a personalised review message; otherwise it returns {@code NO_REVIEW}
     * and this method stays silent for that user.
     *
     * <p>This allows the same cron expression to cover all users without spamming
     * users who have nothing due today.
     *
     * <p>Package-private to allow direct invocation from unit tests without wiring up
     * a real cron scheduler.
     */
    void fireSpacedReview(ScheduleRepository.AgentSchedule schedule) {
        Optional<AgentConfig> agentOpt = agentRepo.findById(schedule.agentId());
        if (agentOpt.isEmpty() || !agentOpt.get().enabled()) {
            log.info("SR schedule #{}: agent '{}' not found or disabled — skipping.",
                    schedule.id(), schedule.agentId());
            return;
        }
        AgentConfig agent = agentOpt.get();

        if (!llmRegistry.has(agent.llmBackendId())) {
            log.warn("SR schedule #{}: LLM backend '{}' not available — skipping.",
                    schedule.id(), agent.llmBackendId());
            return;
        }
        LlmBackend llm = llmRegistry.get(agent.llmBackendId());

        Optional<StorageBackend> storageOpt = storageRegistry.find(agent.storageBackendId());
        if (storageOpt.isEmpty()) {
            log.warn("SR schedule #{}: storage backend '{}' not available — skipping.",
                    schedule.id(), agent.storageBackendId());
            return;
        }
        StorageBackend storage = storageOpt.get();

        String today = LocalDate.now(timezone).format(DateTimeFormatter.ISO_LOCAL_DATE);
        String srPrompt = loadSrPrompt().replace("{date}", today);

        List<String> users = agentRepo.findUsersByAgent(agent.id());
        if (users.isEmpty()) {
            log.debug("SR schedule #{}: no users found for agent '{}' — nothing to review.",
                    schedule.id(), agent.id());
            return;
        }

        log.info("SR schedule #{} firing: agent='{}', {} user(s) to check.",
                schedule.id(), agent.id(), users.size());

        for (String canonUserId : users) {
            try {
                reviewForUser(schedule, agent, llm, storage, srPrompt, canonUserId);
            } catch (Exception e) {
                log.warn("SR schedule #{}: review failed for user '{}'.",
                        schedule.id(), canonUserId, e);
            }
        }
    }

    private void reviewForUser(ScheduleRepository.AgentSchedule schedule,
                               AgentConfig agent,
                               LlmBackend llm,
                               StorageBackend storage,
                               String srPrompt,
                               String canonUserId) {
        String memoryPath = memoryPath(agent.id(), canonUserId);
        Optional<String> memoryOpt;
        try {
            memoryOpt = storage.read(memoryPath);
        } catch (dev.coachbot.storage.StorageException e) {
            log.warn("SR schedule #{}: could not read memory for user '{}' — skipping.",
                    schedule.id(), canonUserId, e);
            return;
        }

        if (memoryOpt.isEmpty()) {
            log.info("SR schedule #{}: no memory for user '{}' — bootstrapping initial topics.",
                    schedule.id(), canonUserId);
            String bootstrapped = bootstrapMemory(schedule, agent, llm, storage, memoryPath);
            if (bootstrapped == null) {
                log.debug("SR schedule #{}: bootstrap produced nothing for user '{}' — skipping.",
                        schedule.id(), canonUserId);
                return;
            }
            memoryOpt = Optional.of(bootstrapped);
        }

        String response = llm.complete(
                LlmRequest.of(srPrompt, List.of(), memoryOpt.get(), "scheduler", agent.id())
        ).text();

        if (response == null || response.isBlank() || response.trim().equals("NO_REVIEW")) {
            log.debug("SR schedule #{}: nothing due for user '{}'.", schedule.id(), canonUserId);
            return;
        }

        // Deliver to all transport keys known for this user
        List<String> transportKeys = identityStore.findTransportKeysByCanonicalId(canonUserId);
        if (transportKeys.isEmpty()) {
            log.warn("SR schedule #{}: no transport keys for user '{}' — cannot deliver review.",
                    schedule.id(), canonUserId);
            return;
        }

        for (String key : transportKeys) {
            int colon = key.indexOf(':');
            if (colon < 1) {
                log.warn("SR schedule #{}: malformed transport key '{}' — skipping.", schedule.id(), key);
                continue;
            }
            String transportId = key.substring(0, colon);
            String chatId      = key.substring(colon + 1);
            transportRegistry.find(transportId).ifPresentOrElse(
                    t -> {
                        t.send(chatId, response);
                        log.info("SR schedule #{}: review sent to {}:{}", schedule.id(), transportId, chatId);
                    },
                    () -> log.warn("SR schedule #{}: transport '{}' not registered — skipping chatId={}.",
                            schedule.id(), transportId, chatId)
            );
        }
    }

    /**
     * Generates an initial memory document for a user who has no memory file yet.
     *
     * <p>Builds a user message from the agent's system prompt and its enabled commands,
     * then calls the LLM with the bootstrap meta-prompt to synthesise an initial Topic
     * Statistics table.  The result is written to storage and returned so the caller can
     * immediately proceed to the SR review without a second storage read.
     *
     * @return the bootstrapped memory document, or {@code null} if generation failed
     */
    private String bootstrapMemory(ScheduleRepository.AgentSchedule schedule,
                                   AgentConfig agent,
                                   LlmBackend llm,
                                   StorageBackend storage,
                                   String memoryPath) {
        // Build description: system prompt + optional command list
        List<CommandRepository.AgentCommand> commands;
        try {
            commands = commandRepo.findEnabledByAgent(agent.id());
        } catch (Exception e) {
            log.warn("Bootstrap #{}: could not load commands for agent '{}' — using system prompt only.",
                    schedule.id(), agent.id(), e);
            commands = List.of();
        }

        StringBuilder input = new StringBuilder("Agent system prompt:\n").append(agent.systemPrompt());
        if (!commands.isEmpty()) {
            input.append("\n\nAvailable commands:\n");
            for (CommandRepository.AgentCommand cmd : commands) {
                input.append(cmd.trigger()).append(" — ").append(cmd.description()).append("\n");
            }
        }

        String bootstrapPrompt;
        try {
            bootstrapPrompt = loadBootstrapPrompt();
        } catch (Exception e) {
            log.error("Bootstrap #{}: cannot load bootstrap prompt from classpath.", schedule.id(), e);
            return null;
        }

        String memoryContent;
        try {
            memoryContent = llm.complete(
                    LlmRequest.of(bootstrapPrompt, List.of(), input.toString(), "scheduler", agent.id())
            ).text();
        } catch (Exception e) {
            log.warn("Bootstrap #{}: LLM call failed.", schedule.id(), e);
            return null;
        }

        if (memoryContent == null || memoryContent.isBlank()) {
            log.warn("Bootstrap #{}: LLM returned empty content — skipping.", schedule.id());
            return null;
        }

        try {
            storage.write(memoryPath, memoryContent);
            log.info("Bootstrap #{}: memory initialised at '{}'.", schedule.id(), memoryPath);
        } catch (Exception e) {
            // Log but don't fail — user still gets their first review
            log.warn("Bootstrap #{}: could not persist memory to '{}' — review will still fire.",
                    schedule.id(), memoryPath, e);
        }
        return memoryContent;
    }

    /** Returns the canonical storage path for a user's memory document. */
    private String memoryPath(String agentId, String canonUserId) {
        return "coach-bot/memory/" + agentId + "/" + safeFilename(canonUserId) + ".md";
    }

    /** Lazy loader for the spaced-review meta-prompt template. */
    private String loadSrPrompt() {
        if (srPromptTemplate != null) return srPromptTemplate;
        try {
            var resource = new ClassPathResource(SR_PROMPT_PATH);
            srPromptTemplate = resource.getContentAsString(StandardCharsets.UTF_8);
            return srPromptTemplate;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot load spaced-review meta-prompt from classpath: " + SR_PROMPT_PATH, e);
        }
    }

    /** Lazy loader for the memory-bootstrap meta-prompt template. */
    private String loadBootstrapPrompt() {
        if (bootstrapPromptTemplate != null) return bootstrapPromptTemplate;
        try {
            var resource = new ClassPathResource(BOOTSTRAP_PROMPT_PATH);
            bootstrapPromptTemplate = resource.getContentAsString(StandardCharsets.UTF_8);
            return bootstrapPromptTemplate;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot load memory-bootstrap meta-prompt from classpath: " + BOOTSTRAP_PROMPT_PATH, e);
        }
    }

    /**
     * Converts a canonical user ID to a safe filename segment, matching
     * the convention used by {@code MemoryService}.
     * Example: {@code "user:abc123"} → {@code "user_abc123"}
     */
    private static String safeFilename(String canonUserId) {
        return canonUserId.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Converts a 5-field Unix cron to a 6-field Spring cron by prepending {@code "0 "} (seconds=0).
     * If the expression already has 6 fields it is returned unchanged.
     */
    static String toSpringCron(String unixCron) {
        String trimmed = unixCron.trim();
        long fields = Arrays.stream(trimmed.split("\\s+")).count();
        return fields == 5 ? "0 " + trimmed : trimmed;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
