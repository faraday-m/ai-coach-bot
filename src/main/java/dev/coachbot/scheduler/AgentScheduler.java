package dev.coachbot.scheduler;

import dev.coachbot.core.AgentConfig;
import dev.coachbot.core.AgentRepository;
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
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

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
public class AgentScheduler {

    private static final Logger log = LoggerFactory.getLogger(AgentScheduler.class);

    private final ScheduleRepository scheduleRepo;
    private final AgentRepository agentRepo;
    private final LlmBackendRegistry llmRegistry;
    private final TransportRegistry transportRegistry;
    private final StorageBackendRegistry storageRegistry;
    private final ZoneId timezone;

    private final ScheduledExecutorService executor =
            Executors.newScheduledThreadPool(4, Thread.ofVirtual().factory());

    /** Active futures keyed by schedule ID — used for cancellation. */
    private final ConcurrentHashMap<Long, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    public AgentScheduler(ScheduleRepository scheduleRepo,
                          AgentRepository agentRepo,
                          LlmBackendRegistry llmRegistry,
                          TransportRegistry transportRegistry,
                          StorageBackendRegistry storageRegistry,
                          @org.springframework.beans.factory.annotation.Value("${bot.timezone:UTC}") String timezone) {
        this.scheduleRepo      = scheduleRepo;
        this.agentRepo         = agentRepo;
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
            return;
        }
        for (ScheduleRepository.AgentSchedule s : schedules) {
            register(s);
        }
        log.info("Registered {} agent schedule(s).", schedules.size());
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
                fire(schedule);
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
