package dev.coachbot.onboarding;

import dev.coachbot.llm.ConversationMessage;
import dev.coachbot.llm.LlmBackend;
import dev.coachbot.llm.LlmRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finite-state machine that collects a user profile via a question-by-question conversation,
 * then generates a personalised system prompt by calling the LLM with a meta-prompt template.
 *
 * <p>Questions are provided at construction time so they can be pre-translated into any language
 * via {@link dev.coachbot.translation.TranslationService} before the flow starts.
 * Use the no-arg constructor for English defaults.
 *
 * <p>Usage:
 * <pre>
 *   // English (default)
 *   OnboardingFlow flow = new OnboardingFlow();
 *
 *   // Translated
 *   List&lt;String&gt; translated = translationService.translateAll(OnboardingFlow.defaultQuestions()).texts();
 *   OnboardingFlow flow = new OnboardingFlow(translated);
 *
 *   String firstQuestion = flow.start();             // send to user
 *   StepResult r = flow.answer(userInput, llm, metaPrompt);
 *   // keep calling answer() until r instanceof Done
 * </pre>
 */
public class OnboardingFlow {

    // ── Question metadata (keys + English defaults) ───────────────────────────

    /** Internal keys used to label answers in the generated profile. */
    private static final List<String> STEP_KEYS = List.of(
            "topic", "level", "strengths", "weaknesses", "goal", "language", "tone", "detail", "session_depth"
    );

    /** English question texts — used when no translation is provided. */
    private static final List<String> DEFAULT_QUESTIONS = List.of(
            "What topic or skill do you want to practise? " +
                    "(e.g. Java interviews, English conversation, system design)",
            "What is your current level in this area? " +
                    "(e.g. 2 years Java, intermediate English, no experience)",
            "What are your strengths? What do you already know well?",
            "What areas feel hardest or most important to improve?",
            "What is your goal? " +
                    "(e.g. pass a senior engineer interview, reach B2 English, get a promotion)",
            "What language should the coach use to communicate with you? " +
                    "(e.g. English, Russian, both)",
            "What tone do you prefer from the coach? " +
                    "(e.g. casual and friendly / formal and professional / direct and no-nonsense)",
            "How detailed should the answers be? " +
                    "(e.g. brief and focused / thorough with examples and explanations)",
            "How long should the coach work through a single topic with you? " +
                    "(e.g. quick — 1-2 messages and move on / " +
                    "standard — a few exchanges until you get it / " +
                    "deep dive — keep going until the topic is fully covered)"
    );

    /** Human-readable labels used when building the profile text for the LLM. */
    private static final Map<String, String> KEY_LABELS = Map.of(
            "topic",         "Topic / skill",
            "level",         "Current level",
            "strengths",     "Strengths",
            "weaknesses",    "Areas to improve",
            "goal",          "Goal",
            "language",      "Preferred language",
            "tone",          "Communication tone",
            "detail",        "Answer style",
            "session_depth", "Session depth"
    );

    // ── Instance state ────────────────────────────────────────────────────────

    /** The questions to ask in order — may be translated. */
    private final List<String> questions;
    private final Map<String, String> answers = new LinkedHashMap<>();
    private int currentStep = 0;

    // ── Constructors ──────────────────────────────────────────────────────────

    /** Creates a flow with English default questions. */
    public OnboardingFlow() {
        this(DEFAULT_QUESTIONS);
    }

    /**
     * Creates a flow with custom (e.g. translated) questions.
     *
     * @param questions list of question strings; must have exactly {@code totalSteps()} entries
     * @throws IllegalArgumentException if the list size doesn't match the number of steps
     */
    public OnboardingFlow(List<String> questions) {
        if (questions.size() != STEP_KEYS.size()) {
            throw new IllegalArgumentException(
                    "Expected " + STEP_KEYS.size() + " questions, got " + questions.size());
        }
        this.questions = List.copyOf(questions);
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    /**
     * Returns the default English question texts in order.
     * Pass this list to {@link dev.coachbot.translation.TranslationService#translateAll} to get
     * translated versions, then construct {@code new OnboardingFlow(translatedList)}.
     */
    public static List<String> defaultQuestions() {
        return DEFAULT_QUESTIONS;
    }

    // ── Result type ────────────────────────────────────────────────────────────

    /** Sealed result returned by {@link #answer(String, LlmBackend, String)}. */
    public sealed interface StepResult {
        /** There are more questions to ask. */
        record NextQuestion(String question) implements StepResult {}
        /** All questions answered; the generated prompt is ready. */
        record Done(String generatedPrompt) implements StepResult {}
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Returns the first question. Call this once to start the flow. */
    public String start() {
        return questions.get(0);
    }

    /**
     * Records the user's answer and advances the FSM.
     *
     * @param input       raw user input for the current step
     * @param llm         LLM backend used to generate the final prompt (only called on the last step)
     * @param metaPrompt  the meta-prompt template (must contain {@code {profile}})
     * @return {@link StepResult.NextQuestion} if more questions remain,
     *         or {@link StepResult.Done} with the generated system prompt
     */
    public StepResult answer(String input, LlmBackend llm, String metaPrompt) {
        String key = STEP_KEYS.get(currentStep);
        answers.put(key, input.trim());
        currentStep++;

        if (currentStep < questions.size()) {
            return new StepResult.NextQuestion(questions.get(currentStep));
        }

        // All steps answered — generate the prompt
        String generated = generatePrompt(llm, metaPrompt);
        return new StepResult.Done(generated);
    }

    /** True when the flow has received an answer for every step. */
    public boolean isComplete() {
        return currentStep >= questions.size();
    }

    /** Returns the number of steps already answered (0 before {@link #start()} is called). */
    public int progress() {
        return currentStep;
    }

    /** Total number of questions in the flow. */
    public int totalSteps() {
        return STEP_KEYS.size();
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private String generatePrompt(LlmBackend llm, String metaPromptTemplate) {
        String profile = buildProfileText();
        String systemMessage = metaPromptTemplate.replace("{profile}", profile);

        LlmRequest request = LlmRequest.of(
                systemMessage,
                List.of(),
                "Generate the coaching system prompt based on the profile above.",
                "onboarding",
                "onboarding"
        );
        return llm.complete(request).text();
    }

    private String buildProfileText() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : answers.entrySet()) {
            String label = KEY_LABELS.getOrDefault(e.getKey(), e.getKey());
            sb.append(label).append(": ").append(e.getValue()).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
