package dev.coachbot.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Credentials for the Vaadin admin UI. Set via {@code BOT_WEB_USERNAME} / {@code BOT_WEB_PASSWORD}. */
@Component
@ConfigurationProperties("bot.web")
public class WebProperties {

    private String username = "admin";
    private String password = "coach-bot";

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
