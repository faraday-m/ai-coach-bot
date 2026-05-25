package dev.coachbot.web.views;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import dev.coachbot.core.AgentConfig;
import dev.coachbot.core.AgentRepository;
import dev.coachbot.llm.LlmBackend;
import dev.coachbot.llm.LlmBackendRegistry;
import dev.coachbot.onboarding.OnboardingFlow;
import dev.coachbot.storage.StorageBackendRegistry;
import jakarta.annotation.security.PermitAll;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

@Route(value = "", layout = MainLayout.class)
@PageTitle("Agents — Coach-bot Admin")
@PermitAll
public class AgentsView extends VerticalLayout {

    private final AgentRepository        repo;
    private final LlmBackendRegistry     llmRegistry;
    private final StorageBackendRegistry storageRegistry;
    private final Grid<AgentConfig>      grid = new Grid<>(AgentConfig.class, false);

    /** Lazy-loaded meta-prompt for system-prompt generation — same pattern as GroupSession. */
    private volatile String generateCoachPromptTemplate;

    public AgentsView(AgentRepository repo,
                      LlmBackendRegistry llmRegistry,
                      StorageBackendRegistry storageRegistry) {
        this.repo            = repo;
        this.llmRegistry     = llmRegistry;
        this.storageRegistry = storageRegistry;
        setSizeFull();
        setPadding(true);

        grid.addColumn(AgentConfig::id).setHeader("ID").setAutoWidth(true).setSortable(true);
        grid.addColumn(AgentConfig::name).setHeader("Name").setAutoWidth(true).setSortable(true);
        grid.addColumn(AgentConfig::llmBackendId).setHeader("LLM").setAutoWidth(true);
        grid.addColumn(AgentConfig::storageBackendId).setHeader("Storage").setAutoWidth(true);
        grid.addColumn(AgentConfig::trigger).setHeader("Trigger").setAutoWidth(true);
        grid.addComponentColumn(agent -> {
            var badge = new Span(agent.enabled() ? "enabled" : "disabled");
            badge.getElement().getThemeList().add("badge " + (agent.enabled() ? "success" : "error"));
            return badge;
        }).setHeader("Status").setAutoWidth(true);
        grid.addComponentColumn(agent -> {
            var edit = new Button("Edit");
            edit.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
            edit.addClickListener(e -> UI.getCurrent().navigate(
                    AgentDetailView.class, new RouteParameters("agentId", agent.id())));

            var toggle = new Button(agent.enabled() ? "Disable" : "Enable");
            toggle.addThemeVariants(
                    agent.enabled() ? ButtonVariant.LUMO_ERROR : ButtonVariant.LUMO_SUCCESS,
                    ButtonVariant.LUMO_SMALL);
            toggle.addClickListener(e -> {
                repo.setEnabled(agent.id(), !agent.enabled());
                refresh();
                Notification.show("Agent '" + agent.id() + "' " +
                        (agent.enabled() ? "disabled" : "enabled") + ".");
            });

            return new HorizontalLayout(edit, toggle);
        }).setHeader("Actions");

        grid.setSizeFull();
        grid.addItemDoubleClickListener(e ->
                UI.getCurrent().navigate(AgentDetailView.class,
                        new RouteParameters("agentId", e.getItem().id())));

        var newBtn = new Button("New Agent");
        newBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        newBtn.addClickListener(e -> openCreateDialog());

        var header = new HorizontalLayout(new H2("Agents"), newBtn);
        header.setDefaultVerticalComponentAlignment(Alignment.CENTER);

        add(header, grid);
        expand(grid);
        refresh();
    }

    private void refresh() {
        grid.setItems(repo.findAll());
    }

    // ── Wizard state ──────────────────────────────────────────────────────────

    /**
     * Carries the agent configuration fields collected in Phase 1 (config) through
     * to Phase 4 (review + create).  The system prompt is added only at the end.
     */
    private record AgentDraft(String id, String name, String llmBackendId,
                               String storageBackendId, String trigger, boolean requireTrigger) {}

    // ── Entry point ───────────────────────────────────────────────────────────

    private void openCreateDialog() {
        var dialog = new Dialog();
        dialog.setWidth("560px");
        dialog.setCloseOnEsc(false);
        dialog.setCloseOnOutsideClick(false);
        showConfigPhase(dialog);
        dialog.open();
    }

    // ── Phase 1: Agent configuration ──────────────────────────────────────────

    private void showConfigPhase(Dialog dialog) {
        dialog.removeAll();
        dialog.getFooter().removeAll();
        dialog.setHeaderTitle("New Agent — Configuration");

        var idField = new TextField("ID");
        idField.setPlaceholder("english-coach");
        idField.setWidthFull();

        var nameField = new TextField("Name");
        nameField.setPlaceholder("English Coach");
        nameField.setWidthFull();

        var llmSelect = new Select<String>();
        llmSelect.setLabel("LLM Backend");
        llmSelect.setItems(llmRegistry.available().stream().sorted().toList());
        llmSelect.setHelperText("Enable via BOT_LLM_*_ENABLED=true");
        llmSelect.setWidthFull();

        var storageSelect = new Select<String>();
        storageSelect.setLabel("Storage Backend");
        storageSelect.setItems(storageRegistry.available().stream().sorted().toList());
        storageSelect.setHelperText("Enable obsidian via BOT_STORAGE_OBSIDIAN_ENABLED=true");
        storageSelect.setWidthFull();

        var triggerField = new TextField("Trigger");
        triggerField.setValue("@Coach");
        triggerField.setWidthFull();

        var requireTriggerCheck = new Checkbox("Require trigger word", true);

        var form = new VerticalLayout(
                idField, nameField, llmSelect, storageSelect,
                triggerField, requireTriggerCheck);
        form.setPadding(false);
        form.setSpacing(false);
        dialog.add(form);

        var cancelBtn = new Button("Cancel", e -> dialog.close());

        var nextBtn = new Button("Next: Set up coaching →");
        nextBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        nextBtn.addClickListener(e -> {
            String id = idField.getValue().trim();
            if (id.isBlank())                        { notify("ID is required.", true);                      return; }
            if (repo.findById(id).isPresent())        { notify("ID '" + id + "' already exists.", true);     return; }
            if (llmSelect.getValue() == null)         { notify("Select an LLM backend.", true);              return; }
            if (storageSelect.getValue() == null)     { notify("Select a storage backend.", true);           return; }

            var draft = new AgentDraft(
                    id,
                    nameField.getValue().isBlank() ? id : nameField.getValue().trim(),
                    llmSelect.getValue(),
                    storageSelect.getValue(),
                    triggerField.getValue().isBlank() ? "@Coach" : triggerField.getValue().trim(),
                    requireTriggerCheck.getValue());

            OnboardingFlow flow = new OnboardingFlow();
            showWizardPhase(dialog, draft, flow, flow.start());
        });

        dialog.getFooter().add(cancelBtn, nextBtn);
    }

    // ── Phase 2: Onboarding questions ─────────────────────────────────────────

    /**
     * Displays one OnboardingFlow question at a time.
     *
     * <p>For steps 1–8 the answer is recorded and the next question shown immediately.
     * On the final step (9) a virtual thread is launched to run the LLM and the UI
     * transitions to the generating spinner before the thread finishes.
     *
     * @param questionText the question string to display (either {@code flow.start()}
     *                     or the text from a previous {@link OnboardingFlow.StepResult.NextQuestion})
     */
    private void showWizardPhase(Dialog dialog, AgentDraft draft, OnboardingFlow flow, String questionText) {
        dialog.removeAll();
        dialog.getFooter().removeAll();

        int stepNum       = flow.progress() + 1;          // 1-based current question
        boolean isLast    = stepNum == flow.totalSteps();
        dialog.setHeaderTitle("Setup %d / %d".formatted(stepNum, flow.totalSteps()));

        var questionLabel = new Span(questionText);
        questionLabel.getStyle()
                .set("font-weight", "600")
                .set("white-space", "pre-wrap")
                .set("line-height", "1.5");

        var answerArea = new TextArea("Your answer");
        answerArea.setWidthFull();
        answerArea.setMinHeight("80px");

        var content = new VerticalLayout(questionLabel, answerArea);
        content.setPadding(false);
        content.setSpacing(true);
        dialog.add(content);

        // "Skip" always available — bypasses the remaining questions
        var skipBtn = new Button("Skip — set prompt manually");
        skipBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        skipBtn.addClickListener(e -> showReviewPhase(dialog, draft, ""));

        var nextBtn = new Button(isLast ? "Generate prompt →" : "Next →");
        nextBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        nextBtn.addClickListener(e -> {
            String answer = answerArea.getValue().trim();
            if (answer.isBlank()) {
                notify("Please enter an answer, or click Skip.", true);
                return;
            }
            if (isLast) {
                // Final step: LLM call required — hand off to virtual thread
                showGeneratingPhase(dialog, draft, flow, answer);
            } else {
                // Non-final: record answer (LLM not called) and show next question
                var result = flow.answer(answer, null, null);
                if (result instanceof OnboardingFlow.StepResult.NextQuestion nq) {
                    showWizardPhase(dialog, draft, flow, nq.question());
                }
            }
        });

        dialog.getFooter().add(skipBtn, nextBtn);
    }

    // ── Phase 3: Generating (spinner) ─────────────────────────────────────────

    private void showGeneratingPhase(Dialog dialog, AgentDraft draft, OnboardingFlow flow, String lastAnswer) {
        dialog.removeAll();
        dialog.getFooter().removeAll();
        dialog.setHeaderTitle("Generating coaching prompt…");

        var label = new Span("Analysing your answers and crafting a personalised system prompt. This takes a few seconds…");
        label.getStyle()
                .set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)");

        var bar = new ProgressBar();
        bar.setIndeterminate(true);
        bar.setWidthFull();

        dialog.add(new VerticalLayout(label, bar));

        // Run the LLM call on a virtual thread; push the result back via ui.access()
        UI ui = UI.getCurrent();
        Thread.ofVirtual().start(() -> {
            try {
                String template   = loadGenerateCoachPrompt();
                LlmBackend llm    = llmRegistry.get(draft.llmBackendId());
                var result        = flow.answer(lastAnswer, llm, template);
                String generated  = result instanceof OnboardingFlow.StepResult.Done d
                        ? d.generatedPrompt() : "";
                ui.access(() -> showReviewPhase(dialog, draft, generated));
            } catch (Exception ex) {
                String msg = ex.getMessage();
                ui.access(() -> {
                    notify("Prompt generation failed: " + msg, true);
                    showReviewPhase(dialog, draft, "");
                });
            }
        });
    }

    // ── Phase 4: Review & create ──────────────────────────────────────────────

    private void showReviewPhase(Dialog dialog, AgentDraft draft, String generatedPrompt) {
        dialog.removeAll();
        dialog.getFooter().removeAll();
        dialog.setHeaderTitle("New Agent — Review Prompt");

        var hint = new Span("Review and edit the generated prompt before creating the agent.");
        hint.getStyle()
                .set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)");

        var promptArea = new TextArea("System Prompt");
        promptArea.setValue(generatedPrompt);
        promptArea.setPlaceholder("You are a helpful coach…");
        promptArea.setWidthFull();
        promptArea.setMinHeight("220px");

        dialog.add(new VerticalLayout(hint, promptArea));

        var redoBtn = new Button("← Redo wizard");
        redoBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        redoBtn.addClickListener(e -> {
            OnboardingFlow fresh = new OnboardingFlow();
            showWizardPhase(dialog, draft, fresh, fresh.start());
        });

        var createBtn = new Button("Create Agent →");
        createBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        createBtn.addClickListener(e -> {
            var newAgent = new AgentConfig(
                    draft.id(),
                    draft.name(),
                    promptArea.getValue().trim(),
                    draft.llmBackendId(),
                    draft.storageBackendId(),
                    draft.trigger(),
                    draft.requireTrigger(),
                    true);
            repo.insertAgent(newAgent);
            dialog.close();
            refresh();
            notify("Agent '" + draft.id() + "' created.", false);
            UI.getCurrent().navigate(AgentDetailView.class,
                    new RouteParameters("agentId", draft.id()));
        });

        dialog.getFooter().add(redoBtn, createBtn);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Lazy-loads the {@code generate-coach-prompt.md} meta-prompt from the classpath.
     * Uses double-checked locking — same pattern as {@code GroupSession}.
     */
    private String loadGenerateCoachPrompt() {
        if (generateCoachPromptTemplate == null) {
            synchronized (this) {
                if (generateCoachPromptTemplate == null) {
                    try {
                        generateCoachPromptTemplate = new ClassPathResource(
                                "prompts/meta/generate-coach-prompt.md")
                                .getContentAsString(StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to load generate-coach-prompt.md", e);
                    }
                }
            }
        }
        return generateCoachPromptTemplate;
    }

    private static void notify(String message, boolean error) {
        var n = Notification.show(message, 4000, Notification.Position.BOTTOM_START);
        if (error) n.addThemeVariants(NotificationVariant.LUMO_ERROR);
        else       n.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
    }
}
