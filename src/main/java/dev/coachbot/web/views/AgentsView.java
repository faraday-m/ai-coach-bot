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
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import dev.coachbot.core.AgentConfig;
import dev.coachbot.core.AgentRepository;
import dev.coachbot.llm.LlmBackendRegistry;
import dev.coachbot.storage.StorageBackendRegistry;
import jakarta.annotation.security.PermitAll;

import java.util.List;

@Route(value = "", layout = MainLayout.class)
@PageTitle("Agents — Coach-bot Admin")
@PermitAll
public class AgentsView extends VerticalLayout {

    private final AgentRepository repo;
    private final LlmBackendRegistry llmRegistry;
    private final StorageBackendRegistry storageRegistry;
    private final Grid<AgentConfig> grid = new Grid<>(AgentConfig.class, false);

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
            edit.addClickListener(e -> UI.getCurrent().navigate(AgentDetailView.class, new RouteParameters("agentId", agent.id())));

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
                UI.getCurrent().navigate(AgentDetailView.class, new RouteParameters("agentId", e.getItem().id())));

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

    private void openCreateDialog() {
        var dialog = new Dialog();
        dialog.setHeaderTitle("Create Agent");
        dialog.setWidth("480px");

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

        var systemPromptArea = new TextArea("System Prompt");
        systemPromptArea.setPlaceholder("You are a helpful coach…");
        systemPromptArea.setMinHeight("160px");
        systemPromptArea.setWidthFull();

        var formLayout = new VerticalLayout(
                idField, nameField, llmSelect, storageSelect,
                triggerField, requireTriggerCheck, systemPromptArea);
        formLayout.setPadding(false);
        formLayout.setSpacing(false);
        dialog.add(formLayout);

        var cancelBtn = new Button("Cancel", e -> dialog.close());

        var saveBtn = new Button("Create");
        saveBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveBtn.addClickListener(e -> {
            String id = idField.getValue().trim();
            if (id.isBlank()) {
                notify("ID is required.", true);
                return;
            }
            if (repo.findById(id).isPresent()) {
                notify("An agent with ID '" + id + "' already exists.", true);
                return;
            }
            if (llmSelect.getValue() == null) {
                notify("Select an LLM backend.", true);
                return;
            }
            if (storageSelect.getValue() == null) {
                notify("Select a storage backend.", true);
                return;
            }
            var newAgent = new AgentConfig(
                    id,
                    nameField.getValue().isBlank() ? id : nameField.getValue().trim(),
                    systemPromptArea.getValue(),
                    llmSelect.getValue(),
                    storageSelect.getValue(),
                    triggerField.getValue().isBlank() ? "@Coach" : triggerField.getValue().trim(),
                    requireTriggerCheck.getValue(),
                    true);
            repo.insertAgent(newAgent);
            dialog.close();
            refresh();
            notify("Agent '" + id + "' created.", false);
            UI.getCurrent().navigate(AgentDetailView.class, new RouteParameters("agentId", id));
        });

        dialog.getFooter().add(cancelBtn, saveBtn);
        dialog.open();
    }

    private static void notify(String message, boolean error) {
        var n = Notification.show(message, 4000, Notification.Position.BOTTOM_START);
        if (error) n.addThemeVariants(NotificationVariant.LUMO_ERROR);
        else       n.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
    }
}
