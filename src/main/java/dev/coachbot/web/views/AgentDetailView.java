package dev.coachbot.web.views;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Hr;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import dev.coachbot.core.AgentConfig;
import dev.coachbot.core.AgentRepository;
import dev.coachbot.core.AgentRepository.TransportBinding;
import dev.coachbot.core.CommandRepository;
import dev.coachbot.core.CommandRepository.AgentCommand;
import dev.coachbot.core.Orchestrator;
import dev.coachbot.scheduler.AgentScheduler;
import dev.coachbot.scheduler.ScheduleRepository;
import dev.coachbot.scheduler.ScheduleRepository.AgentSchedule;
import jakarta.annotation.security.PermitAll;

@Route(value = "agent/:agentId", layout = MainLayout.class)
@PageTitle("Agent — Coach-bot Admin")
@PermitAll
public class AgentDetailView extends VerticalLayout implements BeforeEnterObserver {

    private static final String PARAM = "agentId";

    private final AgentRepository agentRepo;
    private final CommandRepository commandRepo;
    private final ScheduleRepository scheduleRepo;
    private final Orchestrator orchestrator;
    private final AgentScheduler agentScheduler;

    private AgentConfig agent;

    public AgentDetailView(AgentRepository agentRepo,
                           CommandRepository commandRepo,
                           ScheduleRepository scheduleRepo,
                           Orchestrator orchestrator,
                           AgentScheduler agentScheduler) {
        this.agentRepo      = agentRepo;
        this.commandRepo    = commandRepo;
        this.scheduleRepo   = scheduleRepo;
        this.orchestrator   = orchestrator;
        this.agentScheduler = agentScheduler;
        setSizeFull();
        setPadding(true);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        String agentId = event.getRouteParameters().get(PARAM).orElse("");
        agent = agentRepo.findById(agentId).orElse(null);
        if (agent == null) {
            event.rerouteTo(AgentsView.class);
            return;
        }
        buildUi();
    }

    private void buildUi() {
        removeAll();
        var back = new Button("← Agents", e -> UI.getCurrent().navigate(AgentsView.class));
        back.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);

        var title = new H2(agent.name() + " (" + agent.id() + ")");

        var tabs = new TabSheet();
        tabs.setSizeFull();
        tabs.add("Overview",      overviewTab());
        tabs.add("System Prompt", promptTab());
        tabs.add("Commands",      commandsTab());
        tabs.add("Transports",    transportsTab());
        tabs.add("Schedules",     schedulesTab());

        add(back, title, tabs);
        expand(tabs);
    }

    // ── Overview ──────────────────────────────────────────────────────────────

    private Component overviewTab() {
        var form = new FormLayout();
        form.addFormItem(field(agent.id()),              "ID");
        form.addFormItem(field(agent.llmBackendId()),    "LLM Backend");
        form.addFormItem(field(agent.storageBackendId()),"Storage Backend");

        var nameField = new TextField();
        nameField.setValue(agent.name());
        nameField.setWidthFull();
        form.addFormItem(nameField, "Name");

        var triggerField = new TextField();
        triggerField.setValue(agent.trigger());
        form.addFormItem(triggerField, "Trigger");

        var requireCheck = new Checkbox("Require trigger prefix to respond");
        requireCheck.setValue(agent.requireTrigger());
        form.addFormItem(requireCheck, "");

        var saveSettings = new Button("Save Settings");
        saveSettings.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveSettings.addClickListener(e -> {
            String newName    = nameField.getValue().trim();
            String newTrigger = triggerField.getValue().trim();
            if (newName.isEmpty()) { notify("Name cannot be empty.", true); return; }
            agentRepo.updateAgent(new AgentConfig(
                    agent.id(), newName, agent.systemPrompt(),
                    agent.llmBackendId(), agent.storageBackendId(),
                    newTrigger, requireCheck.getValue(), agent.enabled()));
            agent = agentRepo.findById(agent.id()).orElse(agent);
            orchestrator.reloadSession(agent.id());
            notify("Settings saved and applied.", false);
        });

        var badge = new Span(agent.enabled() ? "enabled" : "disabled");
        badge.getElement().getThemeList().add("badge " + (agent.enabled() ? "success" : "error"));
        form.addFormItem(badge, "Status");

        var toggle = new Button(agent.enabled() ? "Disable agent" : "Enable agent");
        toggle.addThemeVariants(
                agent.enabled() ? ButtonVariant.LUMO_ERROR : ButtonVariant.LUMO_SUCCESS);
        toggle.addClickListener(e -> {
            agentRepo.setEnabled(agent.id(), !agent.enabled());
            agent = agentRepo.findById(agent.id()).orElse(agent);
            buildUi();
            notify("Agent " + (agent.enabled() ? "enabled" : "disabled") + ".", false);
        });

        var layout = new VerticalLayout(form, saveSettings, new Hr(), toggle);
        layout.setPadding(false);
        return layout;
    }

    // ── System Prompt ─────────────────────────────────────────────────────────

    private Component promptTab() {
        var area = new TextArea();
        area.setWidthFull();
        area.setMinHeight("400px");
        area.setValue(agent.systemPrompt() != null ? agent.systemPrompt() : "");
        area.setPlaceholder("Enter the system prompt for this agent…");

        var charCount = new Span(area.getValue().length() + " chars");
        area.addValueChangeListener(e -> charCount.setText(e.getValue().length() + " chars"));

        var save = new Button("Save Prompt");
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        save.addClickListener(e -> {
            agentRepo.updateSystemPrompt(agent.id(), area.getValue());
            agent = agentRepo.findById(agent.id()).orElse(agent);
            orchestrator.reloadSession(agent.id());
            notify("System prompt saved and applied.", false);
        });

        var toolbar = new HorizontalLayout(save, charCount);
        toolbar.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.CENTER);

        var layout = new VerticalLayout(area, toolbar);
        layout.setSizeFull();
        layout.expand(area);
        layout.setPadding(false);
        return layout;
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    private Component commandsTab() {
        var grid = new Grid<AgentCommand>(AgentCommand.class, false);
        grid.addColumn(AgentCommand::trigger).setHeader("Trigger").setAutoWidth(true);
        grid.addColumn(AgentCommand::description).setHeader("Description").setFlexGrow(1);
        grid.addComponentColumn(cmd -> {
            var edit = new Button("Edit");
            edit.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
            edit.addClickListener(e -> openEditCommandDialog(cmd, grid));

            var toggle = new Button(cmd.enabled() ? "Disable" : "Enable");
            toggle.addThemeVariants(
                    cmd.enabled() ? ButtonVariant.LUMO_ERROR : ButtonVariant.LUMO_SUCCESS,
                    ButtonVariant.LUMO_SMALL);
            toggle.addClickListener(e -> {
                commandRepo.setEnabled(cmd.id(), !cmd.enabled());
                grid.setItems(commandRepo.findByAgent(agent.id()));
                orchestrator.reloadSession(agent.id());
            });

            var del = new Button("Delete");
            del.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
            del.addClickListener(e -> {
                commandRepo.delete(cmd.id());
                grid.setItems(commandRepo.findByAgent(agent.id()));
                orchestrator.reloadSession(agent.id());
                notify("Command deleted.", false);
            });

            return new HorizontalLayout(edit, toggle, del);
        }).setHeader("Actions").setAutoWidth(true);

        grid.setAllRowsVisible(true);
        grid.setItems(commandRepo.findByAgent(agent.id()));

        var triggerField = new TextField();
        triggerField.setPlaceholder("/quiz");
        triggerField.setWidth("120px");

        var descField = new TextField();
        descField.setPlaceholder("Give a random Java interview question");
        descField.setWidthFull();

        var addBtn = new Button("Add");
        addBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        addBtn.addClickListener(e -> {
            String trigger = triggerField.getValue().trim();
            String desc    = descField.getValue().trim();
            if (trigger.isEmpty() || desc.isEmpty()) {
                notify("Both trigger and description are required.", true);
                return;
            }
            if (!trigger.startsWith("/")) trigger = "/" + trigger;
            commandRepo.insert(agent.id(), trigger, desc);
            grid.setItems(commandRepo.findByAgent(agent.id()));
            triggerField.clear();
            descField.clear();
            orchestrator.reloadSession(agent.id());
            notify("Command added and applied.", false);
        });

        var addRow = new HorizontalLayout(triggerField, descField, addBtn);
        addRow.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.BASELINE);
        addRow.setWidthFull();
        addRow.expand(descField);

        var layout = new VerticalLayout(
                new H3("Commands"), grid,
                new Hr(), new Paragraph("Add command:"), addRow);
        layout.setPadding(false);
        return layout;
    }

    private void openEditCommandDialog(AgentCommand cmd, Grid<AgentCommand> grid) {
        var dialog = new Dialog();
        dialog.setHeaderTitle("Edit Command");
        dialog.setWidth("480px");

        var triggerField = new TextField("Trigger");
        triggerField.setValue(cmd.trigger());
        triggerField.setWidthFull();

        var descField = new TextField("Description");
        descField.setValue(cmd.description());
        descField.setWidthFull();

        var content = new VerticalLayout(triggerField, descField);
        content.setPadding(false);
        dialog.add(content);

        var cancel = new Button("Cancel", e -> dialog.close());
        var save = new Button("Save", e -> {
            String trigger = triggerField.getValue().trim();
            String desc    = descField.getValue().trim();
            if (trigger.isEmpty() || desc.isEmpty()) { notify("Both fields are required.", true); return; }
            if (!trigger.startsWith("/")) trigger = "/" + trigger;
            commandRepo.update(cmd.id(), trigger, desc);
            grid.setItems(commandRepo.findByAgent(agent.id()));
            orchestrator.reloadSession(agent.id());
            dialog.close();
            notify("Command updated and applied.", false);
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(cancel, save);
        dialog.open();
    }

    // ── Transports ────────────────────────────────────────────────────────────

    private Component transportsTab() {
        var grid = new Grid<TransportBinding>(TransportBinding.class, false);
        grid.addColumn(TransportBinding::transportId).setHeader("Transport").setAutoWidth(true);
        grid.addColumn(TransportBinding::chatId).setHeader("Chat ID").setFlexGrow(1);
        grid.addComponentColumn(binding -> {
            var edit = new Button("Edit");
            edit.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
            edit.addClickListener(e -> openEditTransportDialog(binding, grid));

            var del = new Button("Remove");
            del.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
            del.addClickListener(e -> {
                agentRepo.deleteTransport(agent.id(), binding.transportId(), binding.chatId());
                grid.setItems(agentRepo.findTransports(agent.id()));
                notify("Transport binding removed.", false);
            });
            return new HorizontalLayout(edit, del);
        }).setHeader("Actions").setAutoWidth(true);

        grid.setAllRowsVisible(true);
        grid.setItems(agentRepo.findTransports(agent.id()));

        var transportField = new TextField();
        transportField.setPlaceholder("telegram");
        transportField.setWidth("120px");

        var chatIdField = new TextField();
        chatIdField.setPlaceholder("-1001234567890");
        chatIdField.setWidthFull();

        var addBtn = new Button("Add");
        addBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        addBtn.addClickListener(e -> {
            String transport = transportField.getValue().trim();
            String chatId    = chatIdField.getValue().trim();
            if (transport.isEmpty() || chatId.isEmpty()) {
                notify("Both transport and chat ID are required.", true);
                return;
            }
            agentRepo.insertTransport(agent.id(), transport, chatId);
            grid.setItems(agentRepo.findTransports(agent.id()));
            transportField.clear();
            chatIdField.clear();
            notify("Transport binding added.", false);
        });

        var addRow = new HorizontalLayout(transportField, chatIdField, addBtn);
        addRow.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.BASELINE);
        addRow.setWidthFull();
        addRow.expand(chatIdField);

        var layout = new VerticalLayout(
                new H3("Transport Bindings"), grid,
                new Hr(), new Paragraph("Add binding:"), addRow);
        layout.setPadding(false);
        return layout;
    }

    private void openEditTransportDialog(TransportBinding binding, Grid<TransportBinding> grid) {
        var dialog = new Dialog();
        dialog.setHeaderTitle("Edit Transport Binding");
        dialog.setWidth("440px");

        var transportField = new TextField("Transport");
        transportField.setValue(binding.transportId());
        transportField.setWidthFull();

        var chatIdField = new TextField("Chat ID");
        chatIdField.setValue(binding.chatId());
        chatIdField.setWidthFull();

        var content = new VerticalLayout(transportField, chatIdField);
        content.setPadding(false);
        dialog.add(content);

        var cancel = new Button("Cancel", e -> dialog.close());
        var save = new Button("Save", e -> {
            String newTransport = transportField.getValue().trim();
            String newChatId    = chatIdField.getValue().trim();
            if (newTransport.isEmpty() || newChatId.isEmpty()) { notify("Both fields are required.", true); return; }
            agentRepo.deleteTransport(agent.id(), binding.transportId(), binding.chatId());
            agentRepo.insertTransport(agent.id(), newTransport, newChatId);
            grid.setItems(agentRepo.findTransports(agent.id()));
            dialog.close();
            notify("Transport binding updated.", false);
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(cancel, save);
        dialog.open();
    }

    // ── Schedules ─────────────────────────────────────────────────────────────

    private Component schedulesTab() {
        var grid = new Grid<AgentSchedule>(AgentSchedule.class, false);
        grid.addColumn(AgentSchedule::cron).setHeader("Cron").setAutoWidth(true);
        grid.addColumn(AgentSchedule::prompt).setHeader("Prompt").setFlexGrow(1);
        grid.addColumn(s -> s.savePath() != null ? s.savePath() : "—").setHeader("Save to").setAutoWidth(true);
        grid.addComponentColumn(s -> {
            var edit = new Button("Edit");
            edit.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
            edit.addClickListener(e -> openEditScheduleDialog(s, grid));

            var toggle = new Button(s.enabled() ? "Disable" : "Enable");
            toggle.addThemeVariants(
                    s.enabled() ? ButtonVariant.LUMO_ERROR : ButtonVariant.LUMO_SUCCESS,
                    ButtonVariant.LUMO_SMALL);
            toggle.addClickListener(e -> {
                boolean enable = !s.enabled();
                scheduleRepo.setEnabled(s.id(), enable);
                if (enable) agentScheduler.register(new AgentSchedule(s.id(), s.agentId(), s.cron(), s.prompt(), true, s.savePath()));
                else        agentScheduler.cancel(s.id());
                grid.setItems(scheduleRepo.findByAgent(agent.id()));
            });

            var del = new Button("Delete");
            del.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
            del.addClickListener(e -> {
                agentScheduler.cancel(s.id());
                scheduleRepo.delete(s.id());
                grid.setItems(scheduleRepo.findByAgent(agent.id()));
                notify("Schedule deleted.", false);
            });

            return new HorizontalLayout(edit, toggle, del);
        }).setHeader("Actions").setAutoWidth(true);

        grid.setAllRowsVisible(true);
        grid.setItems(scheduleRepo.findByAgent(agent.id()));

        var cronField = new TextField();
        cronField.setPlaceholder("0 9 * * MON-FRI");
        cronField.setWidth("160px");
        cronField.setHelperText("min hour day month weekday");

        var promptField = new TextArea();
        promptField.setPlaceholder("Give me a Java interview question to start the day");
        promptField.setMinHeight("60px");
        promptField.setWidthFull();

        var savePathField = new TextField();
        savePathField.setPlaceholder("journal/{date}  (optional)");
        savePathField.setWidth("200px");
        savePathField.setHelperText("Storage path; {date} = today");

        var addBtn = new Button("Add Schedule");
        addBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        addBtn.addClickListener(e -> {
            String cron     = cronField.getValue().trim();
            String prompt   = promptField.getValue().trim();
            String savePath = savePathField.getValue().trim();
            if (cron.isEmpty() || prompt.isEmpty()) {
                notify("Both cron and prompt are required.", true);
                return;
            }
            long id = scheduleRepo.insert(agent.id(), cron, prompt, savePath);
            agentScheduler.register(new AgentSchedule(id, agent.id(), cron, prompt, true, savePath.isEmpty() ? null : savePath));
            grid.setItems(scheduleRepo.findByAgent(agent.id()));
            cronField.clear();
            promptField.clear();
            savePathField.clear();
            notify("Schedule added and activated.", false);
        });

        var addRow = new HorizontalLayout(cronField, promptField, savePathField, addBtn);
        addRow.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.END);
        addRow.setWidthFull();
        addRow.expand(promptField);

        var layout = new VerticalLayout(
                new H3("Scheduled Messages"), grid,
                new Hr(), new Paragraph("Add schedule:"), addRow);
        layout.setPadding(false);
        return layout;
    }

    private void openEditScheduleDialog(AgentSchedule schedule, Grid<AgentSchedule> grid) {
        var dialog = new Dialog();
        dialog.setHeaderTitle("Edit Schedule");
        dialog.setWidth("500px");

        var cronField = new TextField("Cron expression");
        cronField.setValue(schedule.cron());
        cronField.setHelperText("5 fields: minute hour day month weekday");
        cronField.setWidthFull();

        var promptField = new TextArea("Prompt");
        promptField.setValue(schedule.prompt());
        promptField.setMinHeight("100px");
        promptField.setWidthFull();

        var savePathField = new TextField("Save to (optional)");
        savePathField.setValue(schedule.savePath() != null ? schedule.savePath() : "");
        savePathField.setPlaceholder("journal/{date}");
        savePathField.setHelperText("Storage path; supports {date} placeholder. Leave empty to not save.");
        savePathField.setWidthFull();

        var content = new VerticalLayout(cronField, promptField, savePathField);
        content.setPadding(false);
        dialog.add(content);

        var cancel = new Button("Cancel", e -> dialog.close());
        var save = new Button("Save", e -> {
            String cron     = cronField.getValue().trim();
            String prompt   = promptField.getValue().trim();
            String savePath = savePathField.getValue().trim();
            if (cron.isEmpty() || prompt.isEmpty()) { notify("Both cron and prompt are required.", true); return; }
            scheduleRepo.update(schedule.id(), cron, prompt, savePath);
            agentScheduler.cancel(schedule.id());
            if (schedule.enabled()) {
                agentScheduler.register(new AgentSchedule(schedule.id(), schedule.agentId(), cron, prompt, true,
                        savePath.isEmpty() ? null : savePath));
            }
            grid.setItems(scheduleRepo.findByAgent(agent.id()));
            dialog.close();
            notify(schedule.enabled() ? "Schedule updated and activated." : "Schedule updated.", false);
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(cancel, save);
        dialog.open();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Span field(String value) {
        return new Span(value != null ? value : "—");
    }

    private static void notify(String message, boolean error) {
        var n = Notification.show(message, 4000, Notification.Position.BOTTOM_START);
        if (error) n.addThemeVariants(NotificationVariant.LUMO_ERROR);
        else       n.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
    }
}
