package dev.coachbot.credentials.onecli;

import dev.coachbot.credentials.CredentialProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Bridges to a running OneCLI (or any compatible) credential proxy.
 *
 * <p>OneCLI exposes credentials via a simple HTTP API:
 * <pre>
 *   GET {onecli-url}/credentials/{name}  →  plain-text value
 * </pre>
 *
 * <p>In addition (and this is the main use-case), each LLM backend can be configured
 * to route its API calls <em>through</em> the OneCLI proxy entirely, so credentials
 * never enter the Java process at all — see {@code PROXY MODE} below.
 *
 * <h2>Mode 1 — Credential fetch (this provider)</h2>
 * <pre>
 * bot.credentials.onecli:
 *   enabled: true
 *   url: http://localhost:10254
 *
 * bot.llm.claude:
 *   credential-provider: onecli
 *   api-key-name: ANTHROPIC_API_KEY
 * </pre>
 *
 * <h2>Mode 2 — Transparent HTTP proxy (no credential in JVM)</h2>
 * <pre>
 * # OneCLI (or LiteLLM / any MITM proxy) intercepts LLM calls and injects credentials.
 * # The Java process never sees the raw API key.
 *
 * bot.llm.claude:
 *   base-url: http://localhost:10254/anthropic   ← points at the proxy
 *   # api-key: not needed
 *
 * bot.llm.ollama:
 *   base-url: http://localhost:10254/ollama      ← or Ollama via proxy
 * </pre>
 *
 * Mode 2 requires zero code: just set {@code base-url} in the LLM backend config.
 */
@Component
@ConditionalOnProperty(prefix = "bot.credentials.onecli", name = "enabled", havingValue = "true")
public class OnecliCredentialProvider implements CredentialProvider {

    private static final Logger log = LoggerFactory.getLogger(OnecliCredentialProvider.class);

    private final String baseUrl;
    private final HttpClient httpClient;

    public OnecliCredentialProvider(
            @Value("${bot.credentials.onecli.url:http://localhost:10254}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @PostConstruct
    public void validate() {
        log.info("OneCLI credential provider ready (url={})", baseUrl);
        log.info("Alternatively, configure LLM base-url to use OneCLI as transparent proxy");
    }

    @Override
    public String id() {
        return "onecli";
    }

    /**
     * Fetches a credential value from the OneCLI HTTP API.
     * Falls back to empty if the request fails or the credential is not registered.
     */
    @Override
    public Optional<String> get(String name) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("%s/credentials/%s".formatted(baseUrl, name)))
                    .header("Accept", "text/plain")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String value = response.body().strip();
                return value.isBlank() ? Optional.empty() : Optional.of(value);
            }

            log.debug("OneCLI returned HTTP {} for credential '{}'", response.statusCode(), name);
            return Optional.empty();

        } catch (Exception e) {
            log.warn("Could not fetch credential '{}' from OneCLI ({}): {}",
                    name, baseUrl, e.getMessage());
            return Optional.empty();
        }
    }
}
