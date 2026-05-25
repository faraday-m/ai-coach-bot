package dev.coachbot.web;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;

/**
 * Vaadin application shell configuration.
 *
 * <p>{@code @Push} is declared here (on the {@link AppShellConfigurator}) rather than on
 * {@link dev.coachbot.web.views.MainLayout} — Vaadin only accepts app-level annotations
 * on the shell class, and will throw {@code InvalidApplicationConfigurationException}
 * if they appear elsewhere.
 *
 * <p>Server-Sent Push (WebSocket with long-polling fallback) is needed so that
 * background virtual threads can call {@code ui.access()} to push UI updates — e.g.
 * after the LLM finishes generating a system prompt in the agent-creation wizard.
 */
@Push
public class AppShell implements AppShellConfigurator {
}
