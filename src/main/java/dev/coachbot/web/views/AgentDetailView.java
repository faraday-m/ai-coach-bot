package dev.coachbot.web.views;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
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
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.timepicker.TimePicker;
import com.vaadin.flow.data.provider.DataProvider;

import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import dev.coachbot.core.AgentConfig;
import dev.coachbot.core.AgentRepository;
import dev.coachbot.core.AgentRepository.TransportBinding;
import dev.coachbot.core.CommandRepository;
import dev.coachbot.core.CommandRepository.AgentCommand;
import dev.coachbot.core.MessageRepository;
import dev.coachbot.core.MessageRepository.MessageRow;
import dev.coachbot.core.Orchestrator;
import dev.coachbot.memory.MemoryProgressParser;
import dev.coachbot.memory.MemoryProgressParser.TopicProgress;
import dev.coachbot.scheduler.AgentScheduler;
import dev.coachbot.scheduler.ScheduleRepository;
import dev.coachbot.scheduler.ScheduleRepository.AgentSchedule;
import dev.coachbot.storage.StorageBackend;
import dev.coachbot.storage.StorageBackendRegistry;
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
    private final MessageRepository messageRepo;
    private final StorageBackendRegistry storageRegistry;

    private AgentConfig agent;

    public AgentDetailView(AgentRepository agentRepo,
                           CommandRepository commandRepo,
                           ScheduleRepository scheduleRepo,
                           Orchestrator orchestrator,
                           AgentScheduler agentScheduler,
                           MessageRepository messageRepo,
                           StorageBackendRegistry storageRegistry) {
        this.agentRepo        = agentRepo;
        this.commandRepo      = commandRepo;
        this.scheduleRepo     = scheduleRepo;
        this.orchestrator     = orchestrator;
        this.agentScheduler   = agentScheduler;
        this.messageRepo      = messageRepo;
        this.storageRegistry  = storageRegistry;
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
        tabs.add("Messages",      messagesTab());
        tabs.add("Progress",      progressTab());

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
        grid.addColumn(s -> "spaced_review".equals(s.scheduleType()) ? "♻️ Spaced review" : "Broadcast")
                .setHeader("Type").setAutoWidth(true);
        grid.addColumn(s -> "spaced_review".equals(s.scheduleType()) ? "—" : s.prompt())
                .setHeader("Prompt").setFlexGrow(1);
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
                if (enable) agentScheduler.register(new AgentSchedule(s.id(), s.agentId(), s.cron(), s.prompt(), true, s.savePath(), s.scheduleType()));
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

        var typeSelect = new Select<String>();
        typeSelect.setItems("broadcast", "spaced_review");
        typeSelect.setValue("broadcast");
        typeSelect.setItemLabelGenerator(t -> "spaced_review".equals(t) ? "Spaced review" : "Broadcast");
        typeSelect.setWidth("150px");

        var promptField = new TextArea();
        promptField.setPlaceholder("Give me a Java interview question to start the day");
        promptField.setMinHeight("60px");
        promptField.setWidthFull();

        var savePathField = new TextField();
        savePathField.setPlaceholder("journal/{date}  (optional)");
        savePathField.setWidth("200px");
        savePathField.setHelperText("Storage path; {date} = today");

        // Disable prompt field when spaced_review is chosen — it's not used
        typeSelect.addValueChangeListener(e -> {
            boolean isSr = "spaced_review".equals(e.getValue());
            promptField.setEnabled(!isSr);
            promptField.setHelperText(isSr
                    ? "Not used — built-in spaced-review meta-prompt is applied automatically"
                    : null);
            if (isSr) promptField.clear();
        });

        var addBtn = new Button("Add Schedule");
        addBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        addBtn.addClickListener(e -> {
            String type     = typeSelect.getValue();
            String cron     = cronField.getValue().trim();
            String prompt   = promptField.getValue().trim();
            String savePath = savePathField.getValue().trim();
            if (cron.isEmpty()) {
                notify("Cron expression is required.", true);
                return;
            }
            if (!"spaced_review".equals(type) && prompt.isEmpty()) {
                notify("Prompt is required for broadcast schedules.", true);
                return;
            }
            long id = scheduleRepo.insert(agent.id(), cron, prompt, savePath, type);
            agentScheduler.register(new AgentSchedule(id, agent.id(), cron, prompt, true,
                    savePath.isEmpty() ? null : savePath, type));
            grid.setItems(scheduleRepo.findByAgent(agent.id()));
            cronField.clear();
            promptField.clear();
            savePathField.clear();
            typeSelect.setValue("broadcast");
            notify("Schedule added and activated.", false);
        });

        var addRow = new HorizontalLayout(cronField, typeSelect, promptField, savePathField, addBtn);
        addRow.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.END);
        addRow.setWidthFull();
        addRow.expand(promptField);

        var builderSection = buildCronBuilderSection(cronField);

        var layout = new VerticalLayout(
                new H3("Scheduled Messages"), grid,
                new Hr(), new Paragraph("Add schedule:"), builderSection, addRow);
        layout.setPadding(false);
        return layout;
    }

    private void openEditScheduleDialog(AgentSchedule schedule, Grid<AgentSchedule> grid) {
        var dialog = new Dialog();
        dialog.setHeaderTitle("Edit Schedule");
        dialog.setWidth("560px");

        var cronField = new TextField("Cron expression");
        cronField.setValue(schedule.cron());
        cronField.setHelperText("5 fields: minute hour day month weekday");
        cronField.setWidthFull();

        // Cron builder pre-populates from the existing cron expression if it matches
        // the simple "min hour * * days" pattern; otherwise falls back to defaults.
        var builderSection = buildCronBuilderSection(cronField);

        var typeSelect = new Select<String>();
        typeSelect.setLabel("Type");
        typeSelect.setItems("broadcast", "spaced_review");
        typeSelect.setValue(schedule.scheduleType());
        typeSelect.setItemLabelGenerator(t -> "spaced_review".equals(t) ? "Spaced review" : "Broadcast");
        typeSelect.setWidthFull();

        boolean isSrInitially = "spaced_review".equals(schedule.scheduleType());
        var promptField = new TextArea("Prompt");
        promptField.setValue(schedule.prompt() != null ? schedule.prompt() : "");
        promptField.setMinHeight("100px");
        promptField.setWidthFull();
        promptField.setEnabled(!isSrInitially);
        if (isSrInitially) {
            promptField.setHelperText("Not used — built-in spaced-review meta-prompt is applied automatically");
        }

        var savePathField = new TextField("Save to (optional)");
        savePathField.setValue(schedule.savePath() != null ? schedule.savePath() : "");
        savePathField.setPlaceholder("journal/{date}");
        savePathField.setHelperText("Storage path; supports {date} placeholder. Leave empty to not save.");
        savePathField.setWidthFull();

        typeSelect.addValueChangeListener(e -> {
            boolean isSr = "spaced_review".equals(e.getValue());
            promptField.setEnabled(!isSr);
            promptField.setHelperText(isSr
                    ? "Not used — built-in spaced-review meta-prompt is applied automatically"
                    : null);
            if (isSr) promptField.clear();
        });

        var content = new VerticalLayout(builderSection, cronField, typeSelect, promptField, savePathField);
        content.setPadding(false);
        dialog.add(content);

        var cancel = new Button("Cancel", e -> dialog.close());
        var save = new Button("Save", e -> {
            String type     = typeSelect.getValue();
            String cron     = cronField.getValue().trim();
            String prompt   = promptField.getValue().trim();
            String savePath = savePathField.getValue().trim();
            if (cron.isEmpty()) { notify("Cron expression is required.", true); return; }
            if (!"spaced_review".equals(type) && prompt.isEmpty()) {
                notify("Prompt is required for broadcast schedules.", true); return;
            }
            scheduleRepo.update(schedule.id(), cron, prompt, savePath, type);
            agentScheduler.cancel(schedule.id());
            if (schedule.enabled()) {
                agentScheduler.register(new AgentSchedule(schedule.id(), schedule.agentId(), cron, prompt, true,
                        savePath.isEmpty() ? null : savePath, type));
            }
            grid.setItems(scheduleRepo.findByAgent(agent.id()));
            dialog.close();
            notify(schedule.enabled() ? "Schedule updated and activated." : "Schedule updated.", false);
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(cancel, save);
        dialog.open();
    }

    // ── Messages ──────────────────────────────────────────────────────────────

    private Component messagesTab() {
        // ── Filters ───────────────────────────────────────────────────────────
        var transportFilter = new Select<String>();
        transportFilter.setLabel("Transport");
        transportFilter.setItems("All", "telegram", "webchat", "console");
        transportFilter.setValue("All");
        transportFilter.setWidth("150px");

        var triggerFilter = new Select<String>();
        triggerFilter.setLabel("Trigger");
        triggerFilter.setItems("All", "user_message", "user_command");
        triggerFilter.setValue("All");
        triggerFilter.setWidth("160px");

        var loadBtn = new Button("Load");
        loadBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        var filterRow = new HorizontalLayout(transportFilter, triggerFilter, loadBtn);
        filterRow.setAlignItems(FlexComponent.Alignment.END);

        // ── Grid ──────────────────────────────────────────────────────────────
        var grid = new Grid<MessageRow>(MessageRow.class, false);

        // ID column — click to open full-detail dialog
        grid.addComponentColumn(row -> {
            var btn = new Button(String.valueOf(row.id()));
            btn.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
            btn.addClickListener(e -> openMessageDialog(row));
            return btn;
        }).setHeader("ID").setWidth("80px").setFlexGrow(0).setResizable(true);

        grid.addColumn(MessageRow::createdAt).setHeader("Created").setWidth("190px").setFlexGrow(0).setResizable(true);
        grid.addColumn(MessageRow::userId).setHeader("User").setWidth("140px").setFlexGrow(0).setResizable(true);
        grid.addColumn(MessageRow::role).setHeader("Role").setWidth("80px").setFlexGrow(0).setResizable(true);
        grid.addColumn(MessageRow::transportId).setHeader("Transport").setWidth("110px").setFlexGrow(0).setResizable(true);
        grid.addColumn(MessageRow::triggerType).setHeader("Trigger").setWidth("130px").setFlexGrow(0).setResizable(true);
        grid.addColumn(row -> {
            String c = row.content();
            return c != null && c.length() > 120 ? c.substring(0, 120) + "…" : c;
        }).setHeader("Content (click ID for full text)").setFlexGrow(1).setResizable(true);
        grid.setColumnReorderingAllowed(true);
        grid.setPageSize(25);
        grid.setSizeFull();

        loadBtn.addClickListener(e -> {
            String transport = "All".equals(transportFilter.getValue()) ? null : transportFilter.getValue();
            String trigger   = "All".equals(triggerFilter.getValue())   ? null : triggerFilter.getValue();
            // Lazy DataProvider: fetches 25 rows at a time as the user scrolls.
            // The count query tells the grid when to stop requesting more data.
            grid.setDataProvider(DataProvider.fromCallbacks(
                    query -> messageRepo.findMessages(
                            agent.id(), transport, trigger,
                            query.getOffset(), query.getLimit()).stream(),
                    query -> messageRepo.countMessages(agent.id(), transport, trigger)
            ));
        });

        var layout = new VerticalLayout(filterRow, grid);
        layout.setSizeFull();
        layout.setPadding(false);
        layout.expand(grid);
        return layout;
    }

    /** Opens a read-only dialog showing the full content of a message row. */
    private void openMessageDialog(MessageRow row) {
        var dialog = new Dialog();
        dialog.setHeaderTitle("Message #" + row.id());
        dialog.setWidth("640px");
        dialog.setMaxHeight("80vh");

        var form = new FormLayout();
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 2));
        form.addFormItem(field(row.createdAt()),                       "Created");
        form.addFormItem(field(row.userId()),                         "User");
        form.addFormItem(field(row.role()),                           "Role");
        form.addFormItem(field(row.transportId()),                    "Transport");
        form.addFormItem(field(row.triggerType()),                    "Trigger");

        var content = new TextArea("Content");
        content.setValue(row.content() != null ? row.content() : "");
        content.setWidthFull();
        content.setMinHeight("200px");
        content.setReadOnly(true);

        var close = new Button("Close", e -> dialog.close());
        close.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(close);

        var body = new VerticalLayout(form, content);
        body.setPadding(false);
        dialog.add(body);
        dialog.open();
    }


    // ── Progress ──────────────────────────────────────────────────────────────

    /**
     * Shows per-topic learning progress for a selected canonical user.
     * Reads their memory document from the agent's storage backend and
     * parses the {@code ## Topic Statistics} table.
     */
    private Component progressTab() {
        // ── User selector ─────────────────────────────────────────────────────
        List<String> users = agentRepo.findUsersByAgent(agent.id());

        var userSelect = new Select<String>();
        userSelect.setLabel("User");
        userSelect.setItems(users);
        userSelect.setPlaceholder(users.isEmpty() ? "No users yet" : "Select user…");
        userSelect.setEnabled(!users.isEmpty());
        userSelect.setWidth("300px");

        var loadBtn = new Button("Load Progress");
        loadBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        loadBtn.setEnabled(!users.isEmpty());

        var filterRow = new HorizontalLayout(userSelect, loadBtn);
        filterRow.setAlignItems(FlexComponent.Alignment.END);

        // ── Status label (shown when no data) ─────────────────────────────────
        var statusLabel = new Paragraph();
        statusLabel.setVisible(false);
        statusLabel.getStyle().set("color", "var(--lumo-secondary-text-color)");

        // ── Progress grid ─────────────────────────────────────────────────────
        var grid = new Grid<TopicProgress>(TopicProgress.class, false);
        grid.addColumn(t -> t.emoji() + "  " + t.topic())
                .setHeader("Topic").setFlexGrow(1);
        grid.addColumn(TopicProgress::sessions)
                .setHeader("Sessions").setWidth("110px").setFlexGrow(0);
        grid.addColumn(TopicProgress::lastSeen)
                .setHeader("Last seen").setWidth("130px").setFlexGrow(0);
        grid.addColumn(TopicProgress::level)
                .setHeader("Level").setWidth("170px").setFlexGrow(0);
        grid.addComponentColumn(t -> {
            var bar = new ProgressBar(0.0, 1.0, t.fraction());
            bar.setWidth("160px");
            // Colour the bar: green for Confident, yellow for Familiar, red for Needs work
            String colour = t.fraction() >= 1.0 ? "var(--lumo-success-color)"
                          : t.fraction() >= 0.5 ? "var(--lumo-warning-color)"
                          : "var(--lumo-error-color)";
            bar.getStyle().set("--lumo-primary-color", colour);
            return bar;
        }).setHeader("Progress").setWidth("180px").setFlexGrow(0);
        grid.setAllRowsVisible(true);

        loadBtn.addClickListener(e -> {
            String canonUser = userSelect.getValue();
            if (canonUser == null || canonUser.isBlank()) {
                notify("Select a user first.", true);
                return;
            }
            StorageBackend storage = storageRegistry.find(agent.storageBackendId()).orElse(null);
            if (storage == null) {
                notify("Storage backend '" + agent.storageBackendId() + "' not available.", true);
                return;
            }
            String memPath = "coach-bot/memory/" + agent.id() + "/" + safeFilename(canonUser) + ".md";
            try {
                storage.read(memPath).ifPresentOrElse(
                        doc -> {
                            var topics = MemoryProgressParser.parse(doc);
                            if (topics.isEmpty()) {
                                grid.setItems(List.of());
                                statusLabel.setText("No topics tracked yet for " + canonUser + ".");
                                statusLabel.setVisible(true);
                            } else {
                                grid.setItems(topics);
                                statusLabel.setVisible(false);
                            }
                        },
                        () -> {
                            grid.setItems(List.of());
                            statusLabel.setText("No memory file found for " + canonUser + ".");
                            statusLabel.setVisible(true);
                        });
            } catch (Exception ex) {
                notify("Could not read memory: " + ex.getMessage(), true);
            }
        });

        var layout = new VerticalLayout(filterRow, statusLabel, grid);
        layout.setPadding(false);
        return layout;
    }

    /** Mirrors the convention in {@code MemoryService} — keeps paths consistent. */
    private static String safeFilename(String canonUserId) {
        return canonUserId.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    // ── Cron builder ──────────────────────────────────────────────────────────

    /**
     * Returns a "Quick build" section wired to {@code cronField}.
     *
     * <p>When the time picker or day checkboxes change the cron field is updated live.
     * If {@code cronField} already contains a simple {@code "min hour * * days"}
     * expression the builder pre-populates from it; otherwise it defaults to
     * weekdays at 09:00 and writes that into the cron field.
     */
    private Component buildCronBuilderSection(TextField cronField) {
        var timePicker = new TimePicker();
        timePicker.setLabel("Time");
        timePicker.setWidth("130px");

        var daysGroup = new CheckboxGroup<String>();
        daysGroup.setLabel("Days");
        daysGroup.setItems("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

        // ── Shortcut buttons ──────────────────────────────────────────────────
        var weekdaysBtn = new Button("Weekdays", e ->
                daysGroup.setValue(Set.of("MON", "TUE", "WED", "THU", "FRI")));
        weekdaysBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);

        var dailyBtn = new Button("Daily", e ->
                daysGroup.setValue(Set.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")));
        dailyBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);

        var weekendBtn = new Button("Weekend", e ->
                daysGroup.setValue(Set.of("SAT", "SUN")));
        weekendBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);

        var shortcuts = new HorizontalLayout(weekdaysBtn, dailyBtn, weekendBtn);
        shortcuts.setSpacing(false);
        shortcuts.getStyle().set("gap", "4px");

        // ── Live sync to cronField ────────────────────────────────────────────
        Runnable sync = () -> {
            if (timePicker.getValue() == null || daysGroup.getValue().isEmpty()) return;
            cronField.setValue(buildCron(timePicker.getValue(), daysGroup.getValue()));
        };
        timePicker.addValueChangeListener(e -> sync.run());
        daysGroup.addValueChangeListener(e -> sync.run());

        // ── Pre-populate builder from existing cron or set defaults ───────────
        boolean parsed = tryCronToBuilder(cronField.getValue(), timePicker, daysGroup);
        if (!parsed) {
            timePicker.setValue(LocalTime.of(9, 0));
            daysGroup.setValue(Set.of("MON", "TUE", "WED", "THU", "FRI"));
            if (cronField.getValue().isBlank()) sync.run(); // only write default when field is empty
        }

        var row = new HorizontalLayout(timePicker, daysGroup, shortcuts);
        row.setAlignItems(FlexComponent.Alignment.END);
        row.setWidthFull();

        var label = new Span("⏰ Quick build");
        label.getStyle().set("font-size", "var(--lumo-font-size-s)");
        label.getStyle().set("color", "var(--lumo-secondary-text-color)");
        label.getStyle().set("font-weight", "500");

        var section = new VerticalLayout(label, row);
        section.setPadding(false);
        section.setSpacing(false);
        section.getStyle().set("border-bottom", "1px solid var(--lumo-contrast-20pct)");
        section.getStyle().set("padding-bottom", "var(--lumo-space-s)");
        section.getStyle().set("margin-bottom", "var(--lumo-space-s)");
        return section;
    }

    /**
     * Builds a 5-field cron expression from a time and a set of day names.
     * Example: {@code LocalTime.of(9, 0), {"MON","TUE","WED","THU","FRI"}} → {@code "0 9 * * MON-FRI"}
     */
    private static String buildCron(LocalTime time, Set<String> days) {
        String daysPart;
        Set<String> all7    = Set.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
        Set<String> weekdays = Set.of("MON", "TUE", "WED", "THU", "FRI");
        Set<String> weekend  = Set.of("SAT", "SUN");
        if (days.containsAll(all7)) {
            daysPart = "*";
        } else if (days.equals(weekdays)) {
            daysPart = "MON-FRI";
        } else if (days.equals(weekend)) {
            daysPart = "SAT-SUN";
        } else {
            daysPart = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
                    .stream().filter(days::contains).collect(Collectors.joining(","));
            if (daysPart.isBlank()) daysPart = "*";
        }
        return time.getMinute() + " " + time.getHour() + " * * " + daysPart;
    }

    /**
     * Tries to parse a simple {@code "min hour * * days"} cron expression and
     * populate the builder controls. Returns {@code true} if successful.
     */
    private static boolean tryCronToBuilder(String cron,
                                             TimePicker timePicker,
                                             CheckboxGroup<String> daysGroup) {
        if (cron == null || cron.isBlank()) return false;
        String[] parts = cron.trim().split("\\s+");
        if (parts.length != 5) return false;
        // Only handle simple "min hour * * days" — not complex expressions
        if (!"*".equals(parts[2]) || !"*".equals(parts[3])) return false;
        try {
            int minute = Integer.parseInt(parts[0]);
            int hour   = Integer.parseInt(parts[1]);
            String daysPart = parts[4];
            Set<String> days = new HashSet<>();
            if ("*".equals(daysPart)) {
                days = Set.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
            } else if ("MON-FRI".equalsIgnoreCase(daysPart)) {
                days = Set.of("MON", "TUE", "WED", "THU", "FRI");
            } else if ("SAT-SUN".equalsIgnoreCase(daysPart)) {
                days = Set.of("SAT", "SUN");
            } else {
                for (String d : daysPart.split(",")) days.add(d.trim().toUpperCase());
            }
            timePicker.setValue(LocalTime.of(hour, minute));
            daysGroup.setValue(days);
            return true;
        } catch (Exception ignored) {
            return false;
        }
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
