package dev.coachbot.translation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Translation client backed by the MyMemory public API.
 *
 * <p>MyMemory is free without any API key (up to 5 000 translated characters/day per IP).
 * For higher limits, register at mymemory.translated.net and set {@code bot.translate.api-key}
 * to your e-mail address — this raises the quota to 10 000 chars/day.
 *
 * <p>MyMemory API:
 * <pre>
 *   GET https://api.mymemory.translated.net/get?q={text}&langpair={from}|{to}[&of={email}]
 * </pre>
 */
@Component
public class MyMemoryClient implements TranslationClient {

    private static final Logger log = LoggerFactory.getLogger(MyMemoryClient.class);

    private final String baseUrl;
    private final String email;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public MyMemoryClient(
            @Value("${bot.translate.url:https://api.mymemory.translated.net}") String baseUrl,
            @Value("${bot.translate.api-key:}") String email) {

        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.email   = email;
        this.http    = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        if (StringUtils.hasText(email)) {
            log.info("MyMemory client configured: {} (email={})", this.baseUrl, email);
        } else {
            log.info("MyMemory client configured: {} (anonymous, up to 5k chars/day)", this.baseUrl);
        }
    }

    @Override
    public Optional<String> translate(String text, String from, String to) {
        try {
            String url = baseUrl + "/get"
                    + "?q="        + URLEncoder.encode(text, StandardCharsets.UTF_8)
                    + "&langpair=" + URLEncoder.encode(from + "|" + to, StandardCharsets.UTF_8);
            if (StringUtils.hasText(email)) {
                url += "&of=" + URLEncoder.encode(email, StandardCharsets.UTF_8);
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("MyMemory HTTP {} ({}→{}): {}",
                        response.statusCode(), from, to, truncate(response.body(), 120));
                return Optional.empty();
            }

            MyMemoryResponse body = mapper.readValue(response.body(), MyMemoryResponse.class);

            if (body.responseStatus() != 200) {
                log.warn("MyMemory API error {} ({}→{})", body.responseStatus(), from, to);
                return Optional.empty();
            }

            String result = body.responseData() != null ? body.responseData().translatedText() : null;
            if (result == null || result.isBlank()
                    || result.toUpperCase().startsWith("INVALID")
                    || result.toUpperCase().startsWith("NOT AVAILABLE")) {
                log.warn("MyMemory returned unusable result ({}→{}) for '{}…'",
                        from, to, truncate(text, 30));
                return Optional.empty();
            }

            return Optional.of(result);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("MyMemory request interrupted ({}→{})", from, to);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("MyMemory error ({}→{}): {}", from, to, e.getMessage());
            return Optional.empty();
        }
    }

    // ── JSON types ─────────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MyMemoryResponse(int responseStatus, ResponseData responseData) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResponseData(String translatedText) {}

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static String truncate(String s, int max) {
        if (s == null) return "(null)";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
