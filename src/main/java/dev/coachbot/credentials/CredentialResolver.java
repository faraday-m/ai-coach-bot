package dev.coachbot.credentials;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * Helper used by LLM backends to resolve their credentials.
 *
 * <p>Resolution order (first non-blank value wins):
 * <ol>
 *   <li>Inline value in config ({@code bot.llm.X.api-key}) — supports env-var interpolation.</li>
 *   <li>Credential provider specified by {@code bot.llm.X.credential-provider}
 *       queried with {@code bot.llm.X.api-key-name}.</li>
 *   <li>Default provider ({@code "env"}) queried with {@code bot.llm.X.api-key-name}.</li>
 * </ol>
 *
 * <p>If {@code base-url} is set on the backend, proxy mode is assumed and credentials
 * are not required (the proxy handles authentication).
 */
@Component
public class CredentialResolver {

    private final CredentialProviderRegistry registry;

    public CredentialResolver(CredentialProviderRegistry registry) {
        this.registry = registry;
    }

    /**
     * Resolves an API key for an LLM backend.
     *
     * @param inlineValue     value from {@code bot.llm.X.api-key} (may be blank)
     * @param credentialName  name to query in the provider (e.g. {@code "ANTHROPIC_API_KEY"})
     * @param providerId      provider to use; defaults to {@code "env"} if blank
     * @param proxyBaseUrl    if set AND no key is provided, proxy mode is active (no auth needed);
     *                        if a key IS provided alongside a base-url (e.g. OpenRouter, Groq),
     *                        the key takes priority and is forwarded to the custom endpoint
     * @return the resolved API key, or empty if proxy mode with no key
     * @throws IllegalStateException if no credential can be resolved and proxy mode is inactive
     */
    public Optional<String> resolve(
            String inlineValue,
            String credentialName,
            String providerId,
            String proxyBaseUrl,
            String backendId) {

        // 1. Inline value always wins — even alongside a custom base-url (OpenRouter, Groq, etc.)
        if (StringUtils.hasText(inlineValue)) {
            return Optional.of(inlineValue);
        }

        // Proxy mode with no explicit key: credentials injected by the proxy, not needed here
        if (StringUtils.hasText(proxyBaseUrl)) {
            return Optional.empty();
        }

        // 2. Credential provider
        String effectiveProviderId = StringUtils.hasText(providerId) ? providerId : "env";
        Optional<String> fromProvider = registry.find(effectiveProviderId)
                .flatMap(p -> p.get(credentialName));

        if (fromProvider.isPresent()) {
            return fromProvider;
        }

        // 3. Fall back to "env" if a different provider was specified but failed
        if (!effectiveProviderId.equals("env")) {
            Optional<String> fromEnv = registry.find("env").flatMap(p -> p.get(credentialName));
            if (fromEnv.isPresent()) {
                return fromEnv;
            }
        }

        throw new IllegalStateException(
                "LLM backend '%s': cannot resolve credential '%s'. Options:\n".formatted(backendId, credentialName) +
                "  1. Set bot.llm.%s.api-key (direct)\n".formatted(backendId) +
                "  2. Set env var %s\n".formatted(credentialName) +
                "  3. Configure a credential provider (vault, onecli, …)\n" +
                "  4. Set bot.llm.%s.base-url to use a transparent proxy (no key needed)".formatted(backendId)
        );
    }
}
