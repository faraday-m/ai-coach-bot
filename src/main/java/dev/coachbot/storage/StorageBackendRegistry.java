package dev.coachbot.storage;

import dev.coachbot.plugin.PluginRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StorageBackendRegistry extends PluginRegistry<StorageBackend> {

    private static final Logger log = LoggerFactory.getLogger(StorageBackendRegistry.class);

    public StorageBackendRegistry(@Autowired(required = false) List<StorageBackend> backends) {
        super(backends != null ? backends : List.of());
        log.info("Storage backends registered: {}", available());
    }
}
