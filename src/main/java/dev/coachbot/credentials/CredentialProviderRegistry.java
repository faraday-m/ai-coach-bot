package dev.coachbot.credentials;

import dev.coachbot.plugin.PluginRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CredentialProviderRegistry extends PluginRegistry<CredentialProvider> {

    private static final Logger log = LoggerFactory.getLogger(CredentialProviderRegistry.class);

    public CredentialProviderRegistry(@Autowired(required = false) List<CredentialProvider> providers) {
        super(providers != null ? providers : List.of());
        log.info("Credential providers registered: {}", available());
    }
}
