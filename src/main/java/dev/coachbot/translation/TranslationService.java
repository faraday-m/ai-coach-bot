package dev.coachbot.translation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-level translation service used by the onboarding flow.
 *
 * <p>Features:
 * <ul>
 *   <li>Translates a list of strings from English to the configured {@code bot.language}.</li>
 *   <li>Caches results per target language so repeated onboarding sessions don't re-call the API.</li>
 *   <li>Returns a {@link TranslationResult} that clearly signals whether translation succeeded
 *       or fell back to English — callers can show a warning message to the user.</li>
 *   <li>Never throws — all failures are handled internally.</li>
 * </ul>
 *
 * <p>Configuration:
 * <pre>
 *   bot:
 *     language: ru          # target language (ISO 639-1 code); default: "en" (no translation)
 *     translate:
 *       url: https://api.simplytranslate.ai   # default; override for self-hosted instance
 *       api-key:                               # optional; higher limits with a key
 * </pre>
 */
@Service
public class TranslationService {

    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);
    private static final String SOURCE_LANG = "en";

    private final TranslationClient client;
    private final String targetLanguage;

    /** Cache: targetLang → (sourceText → translatedText) */
    private final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    public TranslationService(TranslationClient client,
                              @Value("${bot.language:en}") String targetLanguage) {
        this.client         = client;
        this.targetLanguage = targetLanguage.toLowerCase().trim();

        if (isTranslationEnabled()) {
            log.info("Translation enabled: {} → {}", SOURCE_LANG, this.targetLanguage);
        } else {
            log.info("Translation disabled (bot.language=en — no translation needed).");
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Translates all texts in {@code sources} from English to {@link #targetLanguage}.
     *
     * <p>If translation is disabled ({@code bot.language=en}) or all calls fail,
     * returns the original English texts with {@link TranslationResult#translationFailed()}
     * set accordingly.
     *
     * @param sources English source texts (order preserved)
     * @return a result holding translated (or original) texts and a failure flag
     */
    public TranslationResult translateAll(List<String> sources) {
        if (!isTranslationEnabled()) {
            return TranslationResult.original(sources);
        }

        Map<String, String> langCache = cache.computeIfAbsent(targetLanguage, k -> new ConcurrentHashMap<>());
        List<String> translated = new ArrayList<>(sources.size());
        boolean anyFailed = false;

        for (String src : sources) {
            // Check cache first
            if (langCache.containsKey(src)) {
                translated.add(langCache.get(src));
                continue;
            }

            var result = client.translate(src, SOURCE_LANG, targetLanguage);
            if (result.isPresent()) {
                String t = result.get();
                langCache.put(src, t);
                translated.add(t);
            } else {
                log.warn("Translation failed for text ({}→{}): '{}…' — using English",
                        SOURCE_LANG, targetLanguage, truncate(src, 40));
                translated.add(src); // fall back to English for this item
                anyFailed = true;
            }
        }

        if (anyFailed) {
            log.warn("One or more onboarding questions could not be translated to '{}'. " +
                    "Check bot.translate.url and network connectivity.", targetLanguage);
        }
        return new TranslationResult(translated, anyFailed);
    }

    /**
     * Translates a single string. Convenience wrapper around {@link #translateAll}.
     * Returns the original English string on failure.
     */
    public String translate(String source) {
        if (!isTranslationEnabled()) return source;
        return translateAll(List.of(source)).texts().get(0);
    }

    /** True when {@code bot.language} is set to something other than English. */
    public boolean isTranslationEnabled() {
        return !SOURCE_LANG.equalsIgnoreCase(targetLanguage);
    }

    public String targetLanguage() {
        return targetLanguage;
    }

    // ── Result type ────────────────────────────────────────────────────────────

    /**
     * Holds translated (or fallback English) texts and a flag that says whether
     * the translation service was unavailable.
     *
     * @param texts             translated texts in the same order as the input
     * @param translationFailed {@code true} if any text fell back to English due to an API error
     */
    public record TranslationResult(List<String> texts, boolean translationFailed) {
        /** Convenience factory for the no-translation case. */
        static TranslationResult original(List<String> sources) {
            return new TranslationResult(sources, false);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
