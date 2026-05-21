package dev.coachbot.transport;

import dev.coachbot.plugin.PluginRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TransportRegistry extends PluginRegistry<TransportPlugin> {

    private static final Logger log = LoggerFactory.getLogger(TransportRegistry.class);

    public TransportRegistry(@Autowired(required = false) List<TransportPlugin> transports) {
        super(transports != null ? transports : List.of());
        log.info("Transports registered: {}", available());
    }
}
