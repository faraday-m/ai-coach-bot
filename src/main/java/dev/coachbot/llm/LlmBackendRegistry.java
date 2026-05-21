package dev.coachbot.llm;

import dev.coachbot.plugin.PluginRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LlmBackendRegistry extends PluginRegistry<LlmBackend> {

    private static final Logger log = LoggerFactory.getLogger(LlmBackendRegistry.class);

    public LlmBackendRegistry(@Autowired(required = false) List<LlmBackend> backends) {
        super(backends != null ? backends : List.of());
        log.info("LLM backends registered: {}", available());
    }
}
