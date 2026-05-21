package dev.coachbot.translation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * HTTP client for a self-hosted SimplyTranslate instance.
 *
 * <p>Not registered as a Spring bean by default — use {@link MyMemoryClient} instead,
 * which works anonymously without any API key.
 * To switch back to SimplyTranslate, annotate this class with {@code @Component @Primary}
 * and remove the annotation from {@link MyMemoryClient}.
 *
 * @deprecated public api.simplytranslate.ai now requires an API key; kept for self-hosted setups
 *
 * <p>Uses the JDK built-in {@link HttpClient} and Jackson (already on the classpath
 * via LangChain4j transitive deps) — no Spring Web dependency required.
 *
 * <p>Anonymous (no-key) access is used by default; set {@code bot.translate.api-key}
 * to use an authenticated tier with higher limits.
 *
 * <p>Never throws — returns {@link Optional#empty()} on any network or parse error
 * so callers can fall back to the source language gracefully.
 */
public class SimplyTranslateClient implements TranslationClient {

    private static final Logger log = LoggerFactory.getLogger(SimplyTranslateClient.class);

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public SimplyTranslateClient(String baseUrl, String apiKey) {

        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey  = apiKey;
        this.http    = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        if (StringUtils.hasText(apiKey)) {
            log.info("SimplyTranslate: using authenticated access ({})", this.baseUrl);
        } else {
            log.info("SimplyTranslate client configured: {} (anonymous)", this.baseUrl);
        }
    }

    /**
     * Translates {@code text} from {@code from} language to {@code to} language.
     *
     * @param text  the source text
     * @param from  BCP-47 language code of the source (e.g. {@code "en"})
     * @param to    BCP-47 language code of the target (e.g. {@code "ru"})
     * @return the translated text, or {@link Optional#empty()} if the call failed
     */
    public Optional<String> translate(String text, String from, String to) {
        try {
            String requestBody = mapper.writeValueAsString(
                    new TranslateRequest(text, from, to));

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/translate"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Origin", "https://simplytranslate.ai")
                    .header("Referer", "https://simplytranslate.ai/")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody));

            if (StringUtils.hasText(apiKey)) {
                reqBuilder.header("X-API-Key", apiKey);
            }

            HttpResponse<String> response = http.send(
                    reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("SimplyTranslate HTTP {} ({}→{}): {}", response.statusCode(), from, to,
                        truncate(response.body(), 120));
                return Optional.empty();
            }

            TranslateResponse body = mapper.readValue(response.body(), TranslateResponse.class);

            if (body.result() == null || body.result().isBlank()) {
                log.warn("SimplyTranslate returned empty result ({}→{}) for '{}…'",
                        from, to, truncate(text, 30));
                return Optional.empty();
            }
            return Optional.of(body.result());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("SimplyTranslate request interrupted ({}→{})", from, to);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("SimplyTranslate error ({}→{}): {}", from, to, e.getMessage());
            return Optional.empty();
        }
    }

    // ── JSON types ─────────────────────────────────────────────────────────────

    record TranslateRequest(String text, String from, String to) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TranslateResponse(String result, String from, String to) {}

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static String truncate(String s, int max) {
        if (s == null) return "(null)";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
