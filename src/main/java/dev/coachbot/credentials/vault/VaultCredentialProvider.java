package dev.coachbot.credentials.vault;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.coachbot.credentials.CredentialProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Credential provider backed by HashiCorp Vault KV v2.
 * Enabled when {@code bot.credentials.vault.enabled=true}.
 *
 * <p>All secrets are stored under a single Vault path (e.g. {@code secret/data/coach-bot}).
 * The credential name is the key within that secret:
 * <pre>
 * vault kv put secret/coach-bot ANTHROPIC_API_KEY=sk-ant-...
 * </pre>
 *
 * <p>Credentials are fetched fresh on every call — supports rotation without restart.
 *
 * <p>Configuration:
 * <pre>
 * bot.credentials.vault:
 *   enabled: true
 *   url: http://localhost:8200        # VAULT_ADDR
 *   token: ${VAULT_TOKEN}             # or use AppRole / K8s auth
 *   secret-path: secret/data/coach-bot
 * </pre>
 */
@Component
@ConditionalOnProperty(prefix = "bot.credentials.vault", name = "enabled", havingValue = "true")
public class VaultCredentialProvider implements CredentialProvider {

    private static final Logger log = LoggerFactory.getLogger(VaultCredentialProvider.class);

    private final String vaultUrl;
    private final String token;
    private final String secretPath;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VaultCredentialProvider(
            @Value("${bot.credentials.vault.url:http://localhost:8200}") String vaultUrl,
            @Value("${bot.credentials.vault.token:}") String token,
            @Value("${bot.credentials.vault.secret-path:secret/data/coach-bot}") String secretPath) {
        this.vaultUrl = vaultUrl.endsWith("/") ? vaultUrl.substring(0, vaultUrl.length() - 1) : vaultUrl;
        this.token = token;
        this.secretPath = secretPath;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @PostConstruct
    public void validate() {
        if (!StringUtils.hasText(token)) {
            throw new IllegalStateException(
                    "Vault credential provider is enabled but VAULT_TOKEN is not set");
        }
        log.info("Vault credential provider ready (url={} path={})", vaultUrl, secretPath);
    }

    @Override
    public String id() {
        return "vault";
    }

    /**
     * Fetches the secret from Vault KV v2 and returns the value for {@code name}.
     * Each call hits Vault to support credential rotation.
     */
    @Override
    public Optional<String> get(String name) {
        try {
            // Vault KV v2: GET /v1/{mount}/data/{path}
            String url = "%s/v1/%s".formatted(vaultUrl, secretPath);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-Vault-Token", token)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Vault returned HTTP {} for path '{}'", response.statusCode(), secretPath);
                return Optional.empty();
            }

            // KV v2 response: { "data": { "data": { "KEY": "value" } } }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode value = root.path("data").path("data").path(name);

            return value.isMissingNode() ? Optional.empty() : Optional.of(value.asText());

        } catch (Exception e) {
            log.error("Failed to fetch credential '{}' from Vault: {}", name, e.getMessage());
            return Optional.empty();
        }
    }
}
