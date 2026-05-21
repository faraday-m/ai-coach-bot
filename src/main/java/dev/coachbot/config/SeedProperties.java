package dev.coachbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Seeds for initial agent configuration. Parsed from the {@code seeds:} block in
 * {@code application.yml} and written to SQLite on first run.
 * After that SQLite is the source of truth — this config is ignored.
 */
@ConfigurationProperties("seeds")
public class SeedProperties {

    private List<AgentSeed> agents = new ArrayList<>();

    public List<AgentSeed> getAgents() { return agents; }
    public void setAgents(List<AgentSeed> agents) { this.agents = agents; }

    public static class AgentSeed {
        private String id;
        private String name;
        /** Inline system prompt (mutually exclusive with systemPromptClasspath). */
        private String systemPrompt;
        /** Load system prompt from classpath resource, e.g. "prompts/java-coach.md". */
        private String systemPromptClasspath;
        private String llmBackend;
        private String storageBackend;
        private String trigger = "@Andy";
        private boolean requireTrigger = true;
        private List<TransportSeed> transports = new ArrayList<>();

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
        public String getSystemPromptClasspath() { return systemPromptClasspath; }
        public void setSystemPromptClasspath(String path) { this.systemPromptClasspath = path; }
        public String getLlmBackend() { return llmBackend; }
        public void setLlmBackend(String llmBackend) { this.llmBackend = llmBackend; }
        public String getStorageBackend() { return storageBackend; }
        public void setStorageBackend(String storageBackend) { this.storageBackend = storageBackend; }
        public String getTrigger() { return trigger; }
        public void setTrigger(String trigger) { this.trigger = trigger; }
        public boolean isRequireTrigger() { return requireTrigger; }
        public void setRequireTrigger(boolean requireTrigger) { this.requireTrigger = requireTrigger; }
        public List<TransportSeed> getTransports() { return transports; }
        public void setTransports(List<TransportSeed> transports) { this.transports = transports; }
    }

    public static class TransportSeed {
        private String transportId;
        private String chatId;

        public String getTransportId() { return transportId; }
        public void setTransportId(String transportId) { this.transportId = transportId; }
        public String getChatId() { return chatId; }
        public void setChatId(String chatId) { this.chatId = chatId; }
    }
}
