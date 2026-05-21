package dev.coachbot.credentials.env;

import dev.coachbot.credentials.CredentialProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Default credential provider: resolves credentials from environment variables
 * and Spring properties (in that priority order, via Spring's {@link Environment}).
 *
 * <p>Always active — id {@code "env"} is the fallback when no provider is configured.
 *
 * <p>Example:
 * <pre>
 * ANTHROPIC_API_KEY=sk-ant-... (env var)
 * → credentialProvider.get("ANTHROPIC_API_KEY") → "sk-ant-..."
 * </pre>
 */
@Component
public class EnvCredentialProvider implements CredentialProvider {

    private final Environment environment;

    public EnvCredentialProvider(Environment environment) {
        this.environment = environment;
    }

    @Override
    public String id() {
        return "env";
    }

    @Override
    public Optional<String> get(String name) {
        String value = environment.getProperty(name);
        if (value == null) {
            // Also try system env directly (handles names with dots, etc.)
            value = System.getenv(name);
        }
        return Optional.ofNullable(value).filter(s -> !s.isBlank());
    }
}
