package dev.coachbot.credentials;

import dev.coachbot.credentials.env.EnvCredentialProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class CredentialResolverTest {

    CredentialProviderRegistry registry;
    CredentialResolver resolver;

    @BeforeEach
    void setUp() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("MY_KEY", "from-spring-env");

        EnvCredentialProvider envProvider = new EnvCredentialProvider(env);
        registry = new CredentialProviderRegistry(List.of(envProvider));
        resolver = new CredentialResolver(registry);
    }

    @Test
    void inline_value_takes_priority() {
        Optional<String> result = resolver.resolve(
                "inline-key", "IGNORED_NAME", "env", "", "test-backend");
        assertThat(result).contains("inline-key");
    }

    @Test
    void provider_used_when_no_inline_value() {
        Optional<String> result = resolver.resolve(
                "", "MY_KEY", "env", "", "test-backend");
        assertThat(result).contains("from-spring-env");
    }

    @Test
    void proxy_mode_returns_empty_without_error() {
        Optional<String> result = resolver.resolve(
                "", "DOES_NOT_MATTER", "env",
                "http://localhost:10254/anthropic",   // base-url = proxy mode
                "test-backend");
        assertThat(result).isEmpty();
    }

    @Test
    void throws_with_helpful_message_when_nothing_resolves() {
        assertThatThrownBy(() ->
                resolver.resolve("", "MISSING_CREDENTIAL", "env", "", "claude"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MISSING_CREDENTIAL")
                .hasMessageContaining("base-url");
    }

    @Test
    void unknown_provider_falls_back_to_env() {
        // "nonexistent" provider doesn't exist → fall back to "env"
        Optional<String> result = resolver.resolve(
                "", "MY_KEY", "nonexistent", "", "test-backend");
        assertThat(result).contains("from-spring-env");
    }

    @Test
    void inline_key_beats_proxy_mode_openrouter_pattern() {
        // OpenRouter / Groq: custom base-url AND explicit API key — key must win.
        // Bug repro: previously base-url triggered proxy mode → empty → 401.
        Optional<String> result = resolver.resolve(
                "sk-or-my-key", "IGNORED", "env",
                "https://openrouter.ai/api/v1",
                "openai");
        assertThat(result).contains("sk-or-my-key");
    }
}
