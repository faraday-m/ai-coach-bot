package dev.coachbot.onboarding;

import dev.coachbot.core.AgentConfig;
import dev.coachbot.core.AgentRepository;
import dev.coachbot.llm.LlmBackend;
import dev.coachbot.translation.TranslationService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;

/**
 * Interactive CLI wizard for the {@code --mode=onboard} launch flag.
 *
 * <p>Usage:
 * <pre>
 *   java -jar coach-bot.jar --mode=onboard
 * </pre>
 *
 * <p>The wizard:
 * <ol>
 *   <li>Lists existing agents and asks which one to configure (or "new").</li>
 *   <li>Translates onboarding questions to the configured language (if {@code bot.language != en}).</li>
 *   <li>Runs {@link OnboardingFlow} — asks profile questions one by one.</li>
 *   <li>Calls the LLM with the meta-prompt to generate a system prompt.</li>
 *   <li>Saves the generated prompt to the agent's DB record.</li>
 * </ol>
 */
public class OnboardWizard {

    private static final String META_PROMPT_PATH = "prompts/meta/generate-coach-prompt.md";

    private final AgentRepository repo;
    private final LlmBackend llm;
    private final TranslationService translationService;
    private final Scanner in = new Scanner(System.in);

    public OnboardWizard(AgentRepository repo,
                         LlmBackend llm,
                         TranslationService translationService) {
        this.repo               = repo;
        this.llm                = llm;
        this.translationService = translationService;
    }

    public void run() {
        println("╔═══════════════════════════════════════════════════╗");
        println("║        Coach-bot Onboarding Wizard                ║");
        println("╚═══════════════════════════════════════════════════╝");
        println("");
        println("This wizard generates a personalised system prompt for a coaching agent.");
        println("Answer the questions about the user and their goals.");
        if (translationService.isTranslationEnabled()) {
            println("(Questions will be translated to: " + translationService.targetLanguage() + ")");
        }
        println("");

        // 1. Choose agent
        String agentId = selectAgent();
        if (agentId == null) {
            println("Cancelled.");
            return;
        }

        // 2. Load meta-prompt template
        String metaPrompt = loadMetaPrompt();

        // 3. Translate questions (if needed)
        OnboardingFlow flow = buildTranslatedFlow();

        // 4. Run the FSM
        println("─────────────────────────────────────────────────────");
        println(stepLabel(1, flow) + flow.start());

        String generatedPrompt = null;
        while (!flow.isComplete()) {
            System.out.print("> ");
            if (!in.hasNextLine()) { println("EOF — cancelled."); return; }
            String input = in.nextLine().trim();
            if (input.isEmpty()) { println("(skipped — please type an answer)"); continue; }

            OnboardingFlow.StepResult result = flow.answer(input, llm, metaPrompt);

            switch (result) {
                case OnboardingFlow.StepResult.NextQuestion nq -> {
                    println("");
                    println(stepLabel(flow.progress() + 1, flow) + nq.question());
                }
                case OnboardingFlow.StepResult.Done done -> {
                    generatedPrompt = done.generatedPrompt();
                }
            }
        }

        if (generatedPrompt == null || generatedPrompt.isBlank()) {
            println("⚠ LLM returned an empty prompt. Aborting — no changes made.");
            return;
        }

        // 5. Preview + confirm
        println("");
        println("─── Generated system prompt ─────────────────────────");
        println(generatedPrompt.substring(0, Math.min(600, generatedPrompt.length())));
        if (generatedPrompt.length() > 600) println("… (truncated for preview)");
        println("─────────────────────────────────────────────────────");
        println("");
        System.out.print("Save this prompt to agent '" + agentId + "'? [Y/n] ");
        String confirm = in.hasNextLine() ? in.nextLine().trim() : "n";
        if (!confirm.isEmpty() && !confirm.equalsIgnoreCase("y")) {
            println("Cancelled — no changes made.");
            return;
        }

        repo.updateSystemPrompt(agentId, generatedPrompt);
        println("✓ System prompt saved (" + generatedPrompt.length() + " chars).");
        println("  Restart the bot to apply the new prompt.");
    }

    // ── Agent selection ────────────────────────────────────────────────────────

    private String selectAgent() {
        List<AgentConfig> agents = repo.findAll();

        if (agents.isEmpty()) {
            println("No agents found in the database.");
            println("Run the bot at least once so the default agent is seeded,");
            println("or use '--mode=manage' → 'agent create' to create one first.");
            return null;
        }

        if (agents.size() == 1) {
            AgentConfig a = agents.get(0);
            println("Configuring agent: " + a.id() + " (" + a.name() + ")");
            return a.id();
        }

        println("Available agents:");
        for (int i = 0; i < agents.size(); i++) {
            AgentConfig a = agents.get(i);
            println("  [" + (i + 1) + "] " + a.id() + " — " + a.name());
        }
        System.out.print("Select agent [1-" + agents.size() + "]: ");

        if (!in.hasNextLine()) return null;
        String input = in.nextLine().trim();

        try {
            int idx = Integer.parseInt(input) - 1;
            if (idx < 0 || idx >= agents.size()) {
                println("Invalid selection.");
                return null;
            }
            return agents.get(idx).id();
        } catch (NumberFormatException e) {
            // Accept agent id directly
            String id = input.toLowerCase();
            boolean found = agents.stream().anyMatch(a -> a.id().equals(id));
            if (!found) { println("Agent '" + id + "' not found."); return null; }
            return id;
        }
    }

    // ── Translation ────────────────────────────────────────────────────────────

    private OnboardingFlow buildTranslatedFlow() {
        if (!translationService.isTranslationEnabled()) {
            return new OnboardingFlow(); // plain English
        }

        println("Translating questions to " + translationService.targetLanguage() + "…");
        var result = translationService.translateAll(OnboardingFlow.defaultQuestions());

        if (result.translationFailed()) {
            println("⚠ Translation service unavailable — showing questions in English.");
        }
        return new OnboardingFlow(result.texts());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private String loadMetaPrompt() {
        try (var stream = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(META_PROMPT_PATH),
                "Meta-prompt not found on classpath: " + META_PROMPT_PATH)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load meta-prompt: " + e.getMessage(), e);
        }
    }

    private static String stepLabel(int step, OnboardingFlow flow) {
        return "[" + step + "/" + flow.totalSteps() + "] ";
    }

    private static void println(String s) {
        System.out.println(s);
    }
}
