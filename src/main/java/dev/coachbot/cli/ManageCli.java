package dev.coachbot.cli;

import dev.coachbot.core.AgentConfig;
import dev.coachbot.core.AgentRepository;
import dev.coachbot.core.AgentRepository.TransportBinding;
import dev.coachbot.core.CommandRepository;
import dev.coachbot.core.CommandRepository.AgentCommand;
import dev.coachbot.scheduler.ScheduleRepository;
import dev.coachbot.scheduler.ScheduleRepository.AgentSchedule;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;

/**
 * Interactive management console — activated with {@code --mode=manage}.
 *
 * <pre>
 * java -jar coach-bot.jar --mode=manage
 *
 * > agent list
 * > agent show default
 * > agent edit default          # opens $EDITOR or inline multiline input
 * > agent create
 * > agent enable default
 * > agent disable default
 * > agent add-transport default telegram -1001234567890
 * > agent rm-transport  default telegram -1001234567890
 * > help
 * > exit
 * </pre>
 */
public class ManageCli {

    private static final String PROMPT = "\n> ";
    private static final String MULTILINE_END = "---";

    private final AgentRepository repo;
    private final CommandRepository commandRepo;
    private final ScheduleRepository scheduleRepo;
    private final Set<String> availableLlm;
    private final Set<String> availableStorage;
    private final Scanner in = new Scanner(System.in);

    public ManageCli(AgentRepository repo,
                     CommandRepository commandRepo,
                     ScheduleRepository scheduleRepo,
                     Set<String> availableLlm,
                     Set<String> availableStorage) {
        this.repo             = repo;
        this.commandRepo      = commandRepo;
        this.scheduleRepo     = scheduleRepo;
        this.availableLlm     = availableLlm;
        this.availableStorage = availableStorage;
    }

    public void run() {
        println("Coach-bot Agent Manager  (type 'help' for commands)");
        println("─────────────────────────────────────────────────");

        while (true) {
            System.out.print(PROMPT);
            if (!in.hasNextLine()) break;
            String line = in.nextLine().trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split("\\s+", 3);
            String cmd  = parts[0].toLowerCase();
            String sub  = parts.length > 1 ? parts[1].toLowerCase() : "";
            String arg  = parts.length > 2 ? parts[2] : "";

            try {
                switch (cmd) {
                    case "agent"    -> handleAgent(sub, arg);
                    case "command"  -> handleCommand(sub, arg);
                    case "schedule" -> handleSchedule(sub, arg);
                    case "help"     -> printHelp();
                    case "exit", "quit" -> { println("Bye."); return; }
                    default -> println("Unknown command. Type 'help'.");
                }
            } catch (Exception e) {
                println("Error: " + e.getMessage());
            }
        }
    }

    // ── Agent sub-commands ─────────────────────────────────────────────────────

    private void handleAgent(String sub, String arg) {
        switch (sub) {
            case "list"   -> agentList();
            case "show"   -> agentShow(requireArg(arg, "Usage: agent show <id>"));
            case "edit"   -> agentEdit(requireArg(arg, "Usage: agent edit <id>"));
            case "create" -> agentCreate();
            case "enable" -> agentSetEnabled(requireArg(arg, "Usage: agent enable <id>"), true);
            case "disable"-> agentSetEnabled(requireArg(arg, "Usage: agent disable <id>"), false);
            case "add-transport", "add-tr" -> {
                String[] p = arg.split("\\s+", 3);
                if (p.length < 3) { println("Usage: agent add-transport <id> <transport> <chatId>"); return; }
                repo.insertTransport(p[0], p[1], p[2]);
                println("✓ Transport added: " + p[1] + " → " + p[2]);
            }
            case "rm-transport", "rm-tr" -> {
                String[] p = arg.split("\\s+", 3);
                if (p.length < 3) { println("Usage: agent rm-transport <id> <transport> <chatId>"); return; }
                repo.deleteTransport(p[0], p[1], p[2]);
                println("✓ Transport removed.");
            }
            default -> println("Unknown agent subcommand. Type 'help'.");
        }
    }

    private void agentList() {
        List<AgentConfig> agents = repo.findAll();
        if (agents.isEmpty()) { println("No agents in database."); return; }

        String fmt = "%-20s %-22s %-12s %-12s %-12s %s%n";
        System.out.printf(fmt, "ID", "NAME", "LLM", "STORAGE", "TRIGGER", "STATUS");
        System.out.println("─".repeat(88));
        for (AgentConfig a : agents) {
            System.out.printf(fmt,
                    truncate(a.id(), 19),
                    truncate(a.name(), 21),
                    truncate(a.llmBackendId(), 11),
                    truncate(a.storageBackendId(), 11),
                    truncate(a.trigger(), 11),
                    a.enabled() ? "enabled" : "DISABLED");
        }
    }

    private void agentShow(String id) {
        AgentConfig a = findOrFail(id);
        List<TransportBinding> transports = repo.findTransports(id);

        println("");
        println("  ID:       " + a.id());
        println("  Name:     " + a.name());
        println("  LLM:      " + a.llmBackendId());
        println("  Storage:  " + a.storageBackendId());
        println("  Trigger:  " + a.trigger() + " (required: " + a.requireTrigger() + ")");
        println("  Status:   " + (a.enabled() ? "enabled" : "DISABLED"));
        if (transports.isEmpty()) {
            println("  Transports: (none)");
        } else {
            println("  Transports:");
            for (TransportBinding t : transports) {
                println("    " + t.transportId() + " → " + t.chatId());
            }
        }
        List<AgentCommand> commands = commandRepo.findByAgent(id);
        if (commands.isEmpty()) {
            println("  Commands: (none — use 'command add " + id + "' to add)");
        } else {
            println("  Commands:");
            for (AgentCommand c : commands) {
                println("    [" + c.id() + "] " + c.trigger()
                        + (c.enabled() ? "" : " (disabled)")
                        + " — " + c.description());
            }
        }
        String prompt = a.systemPrompt();
        if (StringUtils.hasText(prompt)) {
            println("  System prompt (" + prompt.length() + " chars):");
            println("  " + prompt.substring(0, Math.min(400, prompt.length())).replace("\n", "\n  "));
            if (prompt.length() > 400) println("  … (truncated, use 'agent edit " + id + "' to see full)");
        } else {
            println("  System prompt: (empty)");
        }
    }

    private void agentEdit(String id) {
        AgentConfig a = findOrFail(id);

        // Prefer $EDITOR if available; fall back to inline input
        String editor = System.getenv("EDITOR");
        String newPrompt;

        if (StringUtils.hasText(editor)) {
            newPrompt = editWithExternalEditor(editor, a.systemPrompt());
        } else {
            newPrompt = editInline(a.systemPrompt());
        }

        if (newPrompt == null) { println("Cancelled — no changes made."); return; }
        repo.updateSystemPrompt(id, newPrompt);
        println("✓ System prompt saved (" + newPrompt.length() + " chars).");
    }

    private void agentCreate() {
        println("─── Create new agent ───────────────────────────────");

        String id = prompt("Agent ID (letters, numbers, hyphens): ");
        if (id.isEmpty()) { println("Cancelled."); return; }
        if (repo.findById(id).isPresent()) { println("Agent '" + id + "' already exists."); return; }

        String name = prompt("Display name: ");

        println("Available LLM backends: " + availableLlm);
        String llm = prompt("LLM backend: ");
        if (!availableLlm.contains(llm)) {
            println("Warning: '" + llm + "' is not registered. You can change it later.");
        }

        println("Available storage backends: " + availableStorage);
        String storage = prompt("Storage backend: ");

        String trigger = prompt("Trigger word (e.g. @Andy): ");

        String reqStr = prompt("Require trigger? (y/n) [y]: ");
        boolean requireTrigger = !reqStr.equalsIgnoreCase("n");

        println("System prompt (type text, end with '" + MULTILINE_END + "' on its own line):");
        String systemPrompt = readMultiline();

        AgentConfig agent = new AgentConfig(id, name, systemPrompt, llm, storage,
                trigger, requireTrigger, true);
        repo.insertAgent(agent);
        println("✓ Agent '" + id + "' created.");
        println("  Add transports with: agent add-transport " + id + " <transport> <chatId>");
    }

    private void agentSetEnabled(String id, boolean enabled) {
        findOrFail(id); // validate exists
        repo.setEnabled(id, enabled);
        println("✓ Agent '" + id + "' " + (enabled ? "enabled" : "disabled") + ".");
    }

    // ── Input helpers ──────────────────────────────────────────────────────────

    /** Opens $EDITOR on a temp file pre-filled with current content. Returns null on cancel/error. */
    private String editWithExternalEditor(String editor, String current) {
        try {
            Path tmp = Files.createTempFile("coach-bot-prompt-", ".md");
            if (StringUtils.hasText(current)) Files.writeString(tmp, current);

            println("Opening " + editor + " — save and close to apply changes.");
            ProcessBuilder pb = new ProcessBuilder(editor, tmp.toString());
            pb.inheritIO();
            int exit = pb.start().waitFor();

            if (exit != 0) { println("Editor exited with code " + exit + "."); return null; }

            String edited = Files.readString(tmp).stripTrailing();
            Files.deleteIfExists(tmp);
            return edited.isEmpty() ? null : edited;
        } catch (IOException | InterruptedException e) {
            println("Editor error: " + e.getMessage());
            return null;
        }
    }

    /** Inline multiline editor — shows current, then prompts for replacement. */
    private String editInline(String current) {
        println("(Tip: set $EDITOR env var to use your preferred editor instead.)");
        if (StringUtils.hasText(current)) {
            println("Current prompt:");
            println("" + MULTILINE_END);
            println(current);
            println("" + MULTILINE_END);
        }
        println("Enter new prompt ('" + MULTILINE_END + "' on its own line to finish, empty line then '" + MULTILINE_END + "' to cancel):");
        String newPrompt = readMultiline();
        return newPrompt.isBlank() ? null : newPrompt;
    }

    /** Reads lines until a line containing only MULTILINE_END is encountered. */
    private String readMultiline() {
        StringBuilder sb = new StringBuilder();
        while (in.hasNextLine()) {
            String line = in.nextLine();
            if (line.equals(MULTILINE_END)) break;
            if (!sb.isEmpty()) sb.append('\n');
            sb.append(line);
        }
        return sb.toString();
    }

    private String prompt(String label) {
        System.out.print("  " + label);
        return in.hasNextLine() ? in.nextLine().trim() : "";
    }

    // ── Command sub-commands ───────────────────────────────────────────────────

    private void handleCommand(String sub, String arg) {
        switch (sub) {
            case "list" -> commandList(requireArg(arg, "Usage: command list <agent-id>"));
            case "add"  -> commandAdd(arg);
            case "rm", "remove", "delete" -> commandRm(arg);
            case "enable"  -> commandSetEnabled(arg, true);
            case "disable" -> commandSetEnabled(arg, false);
            default -> println("Unknown command subcommand. Type 'help'.");
        }
    }

    private void commandList(String agentId) {
        findOrFail(agentId);
        List<AgentCommand> commands = commandRepo.findByAgent(agentId);
        if (commands.isEmpty()) {
            println("No commands for agent '" + agentId + "'.");
            println("  Add one with: command add " + agentId + " /trigger \"What to do\"");
            return;
        }
        String fmt = "%-6s %-14s %-8s %s%n";
        System.out.printf(fmt, "ID", "TRIGGER", "ENABLED", "DESCRIPTION");
        System.out.println("─".repeat(70));
        for (AgentCommand c : commands) {
            System.out.printf(fmt, c.id(), truncate(c.trigger(), 13),
                    c.enabled() ? "yes" : "no", truncate(c.description(), 44));
        }
    }

    private void commandAdd(String arg) {
        // Format: <agent-id> <trigger> <description...>
        // e.g.: default /quiz "Give me a random Java question"
        List<String> p = parseQuotedTokens(arg);
        if (p.size() < 3) {
            println("Usage: command add <agent-id> <trigger> <description>");
            println("  Example: command add default /quiz \"Give a random Java interview question\"");
            return;
        }
        String agentId     = p.get(0);
        String trigger     = p.get(1);
        String description = String.join(" ", p.subList(2, p.size()));
        if (!trigger.startsWith("/")) trigger = "/" + trigger;
        findOrFail(agentId);
        long id = commandRepo.insert(agentId, trigger, description);
        println("✓ Command #" + id + " added: " + trigger + " → " + description);
        println("  Restart the bot to activate the new command.");
    }

    private void commandRm(String arg) {
        try {
            long id = Long.parseLong(arg.trim());
            commandRepo.delete(id);
            println("✓ Command #" + id + " deleted.");
        } catch (NumberFormatException e) {
            println("Usage: command rm <id>   (use 'command list <agent-id>' to see IDs)");
        }
    }

    private void commandSetEnabled(String arg, boolean enabled) {
        try {
            long id = Long.parseLong(arg.trim());
            commandRepo.setEnabled(id, enabled);
            println("✓ Command #" + id + " " + (enabled ? "enabled" : "disabled") + ".");
        } catch (NumberFormatException e) {
            println("Usage: command enable/disable <id>");
        }
    }

    // ── Schedule sub-commands ──────────────────────────────────────────────────

    private void handleSchedule(String sub, String arg) {
        switch (sub) {
            case "list" -> scheduleList(requireArg(arg, "Usage: schedule list <agent-id>"));
            case "add"  -> scheduleAdd(arg);
            case "rm", "remove", "delete" -> scheduleRm(arg);
            case "enable"  -> scheduleSetEnabled(arg, true);
            case "disable" -> scheduleSetEnabled(arg, false);
            default -> println("Unknown schedule subcommand. Type 'help'.");
        }
    }

    private void scheduleList(String agentId) {
        findOrFail(agentId); // validate exists
        List<AgentSchedule> schedules = scheduleRepo.findByAgent(agentId);
        if (schedules.isEmpty()) {
            println("No schedules for agent '" + agentId + "'.");
            return;
        }
        String fmt = "%-6s %-20s %s%n";
        System.out.printf(fmt, "ID", "CRON", "PROMPT");
        System.out.println("─".repeat(70));
        for (AgentSchedule s : schedules) {
            System.out.printf(fmt,
                    s.id(),
                    truncate(s.cron(), 19),
                    truncate(s.prompt(), 40));
        }
    }

    private void scheduleAdd(String arg) {
        // Format: <agent-id> <cron-5-fields> <prompt...>
        // e.g.: default "0 9 * * MON-FRI" Give me a Java interview question
        // Quotes are required around cron expressions that contain spaces.
        List<String> p = parseQuotedTokens(arg);
        if (p.size() < 3) {
            println("Usage: schedule add <agent-id> <cron> <prompt>");
            println("  Example: schedule add default \"0 9 * * MON-FRI\" Give me a morning Java question");
            return;
        }
        String agentId = p.get(0);
        String cron    = p.get(1);
        String prompt  = String.join(" ", p.subList(2, p.size()));
        findOrFail(agentId);
        long id = scheduleRepo.insert(agentId, cron, prompt, null);
        println("✓ Schedule #" + id + " created: " + cron + " → " + truncate(prompt, 60));
        println("  Restart the bot to activate the new schedule.");
    }

    private void scheduleRm(String arg) {
        try {
            long id = Long.parseLong(arg.trim());
            scheduleRepo.delete(id);
            println("✓ Schedule #" + id + " deleted.");
        } catch (NumberFormatException e) {
            println("Usage: schedule rm <id>   (use 'schedule list <agent-id>' to see IDs)");
        }
    }

    private void scheduleSetEnabled(String arg, boolean enabled) {
        try {
            long id = Long.parseLong(arg.trim());
            scheduleRepo.setEnabled(id, enabled);
            println("✓ Schedule #" + id + " " + (enabled ? "enabled" : "disabled") + ".");
        } catch (NumberFormatException e) {
            println("Usage: schedule enable/disable <id>");
        }
    }

    private AgentConfig findOrFail(String id) {
        return repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Agent '" + id + "' not found. Use 'agent list' to see available agents."));
    }

    private static String requireArg(String arg, String usage) {
        if (arg == null || arg.isBlank()) throw new IllegalArgumentException(usage);
        return arg.trim();
    }

    /**
     * Splits {@code input} into tokens, respecting single- and double-quoted groups.
     * Quotes are stripped; content inside quotes is preserved as a single token even
     * if it contains spaces.  Example:
     * {@code default "* 19 * * *" "Tell a joke"} → ["default", "* 19 * * *", "Tell a joke"]
     */
    static List<String> parseQuotedTokens(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean inDouble = false, inSingle = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == ' ' && !inDouble && !inSingle) {
                if (!token.isEmpty()) { tokens.add(token.toString()); token.setLength(0); }
            } else {
                token.append(c);
            }
        }
        if (!token.isEmpty()) tokens.add(token.toString());
        return tokens;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static void println(String s) {
        System.out.println(s);
    }

    // ── Help ───────────────────────────────────────────────────────────────────

    private static void printHelp() {
        println("""

                Commands:
                  agent list                                     — list all agents
                  agent show   <id>                              — show details, commands, prompt preview
                  agent edit   <id>                              — edit system prompt ($EDITOR or inline)
                  agent create                                   — create a new agent interactively
                  agent enable  <id>                             — enable an agent
                  agent disable <id>                             — disable an agent
                  agent add-transport <id> <transport> <chatId> — add transport binding
                  agent rm-transport  <id> <transport> <chatId> — remove transport binding

                  command list    <agent-id>                     — list slash-commands for an agent
                  command add     <agent-id> <trigger> <desc>   — add a command (/quiz "Give a question")
                  command rm      <id>                           — delete a command by ID
                  command enable  <id>                           — enable a command
                  command disable <id>                           — disable a command

                  schedule list    <agent-id>                    — list cron schedules
                  schedule add     <agent-id> <cron> <prompt>    — add a new cron schedule
                  schedule rm      <id>                          — delete a schedule by ID
                  schedule enable  <id>                          — enable a schedule
                  schedule disable <id>                          — disable a schedule

                  help                                           — this help
                  exit / quit                                    — exit

                Commands are appended to the agent's system prompt so the LLM knows how to
                react when a user types /quiz, /hint, etc. Restart the bot to apply changes.

                Cron format (5 fields): "minute hour day-of-month month day-of-week"
                  Example: "0 9 * * MON-FRI"  — weekdays at 09:00
                  Example: "30 18 * * *"       — every day at 18:30

                Tip: set $EDITOR (e.g. export EDITOR=nano) to edit prompts in your editor.
                """);
    }
}
