package dev.coachbot.credentials;

import dev.coachbot.plugin.Identifiable;

import java.util.Optional;

/**
 * SPI for credential sources — the fourth axis of extensibility alongside
 * Transport, LLM, and Storage.
 *
 * <p>Each LLM backend can reference a credential provider by id to obtain its
 * API key without ever having the raw secret in {@code application.yml}:
 *
 * <pre>
 * bot.llm.claude.api-key-name: ANTHROPIC_API_KEY
 * bot.llm.claude.credential-provider: vault   ← resolved via this SPI
 * </pre>
 *
 * <p>Alternatively, backends support a transparent <em>proxy mode</em>:
 * set {@code bot.llm.claude.base-url} to an HTTP proxy (OneCLI, LiteLLM, …)
 * and no credential is needed in the Java process at all.
 *
 * <p>To add a new credential source:
 * <ol>
 *   <li>Implement this interface.</li>
 *   <li>Annotate with {@code @Component} (+ {@code @ConditionalOnProperty} if optional).</li>
 *   <li>Reference by {@link #id()} in {@code bot.llm.*.credential-provider}.</li>
 * </ol>
 *
 * <p>Implementations MUST NOT cache values — each call should be fresh to support
 * credential rotation without restarting the application.
 */
public interface CredentialProvider extends Identifiable {

    /**
     * Returns the credential value for the given name, or {@code empty()} if unavailable.
     *
     * @param name symbolic name, e.g. {@code "ANTHROPIC_API_KEY"}
     */
    Optional<String> get(String name);
}
