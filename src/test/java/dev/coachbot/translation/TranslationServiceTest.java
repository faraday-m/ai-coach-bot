package dev.coachbot.translation;

import dev.coachbot.onboarding.OnboardingFlow;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TranslationServiceTest {

    // ── Client stubs ───────────────────────────────────────────────────────────

    static final TranslationClient PREFIX  = (t, f, to) -> Optional.of("[" + to + "] " + t);
    static final TranslationClient FAILING = (t, f, to) -> Optional.empty();

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    void translation_disabled_when_language_is_english() {
        var service = new TranslationService(PREFIX, "en");
        assertThat(service.isTranslationEnabled()).isFalse();

        var result = service.translateAll(java.util.List.of("Hello", "World"));
        assertThat(result.translationFailed()).isFalse();
        assertThat(result.texts()).containsExactly("Hello", "World");
    }

    @Test
    void translates_all_texts_when_service_available() {
        var service = new TranslationService(PREFIX, "ru");
        assertThat(service.isTranslationEnabled()).isTrue();

        var result = service.translateAll(java.util.List.of("First", "Second"));
        assertThat(result.translationFailed()).isFalse();
        assertThat(result.texts()).containsExactly("[ru] First", "[ru] Second");
    }

    @Test
    void falls_back_to_english_when_service_unavailable() {
        var service = new TranslationService(FAILING, "ru");

        var result = service.translateAll(java.util.List.of("First", "Second"));
        assertThat(result.translationFailed()).isTrue();
        assertThat(result.texts()).containsExactly("First", "Second");
    }

    @Test
    void partial_failure_uses_english_for_failed_items() {
        int[] calls = {0};
        TranslationClient partial = (t, f, to) ->
                (calls[0]++ == 0) ? Optional.of("Первый") : Optional.empty();

        var service = new TranslationService(partial, "ru");
        var result  = service.translateAll(java.util.List.of("First", "Second"));

        assertThat(result.translationFailed()).isTrue();
        assertThat(result.texts().get(0)).isEqualTo("Первый");
        assertThat(result.texts().get(1)).isEqualTo("Second");
    }

    @Test
    void results_are_cached_per_language() {
        int[] callCount = {0};
        TranslationClient counting = (t, f, to) -> { callCount[0]++; return Optional.of("translated"); };

        var service = new TranslationService(counting, "ru");
        service.translateAll(java.util.List.of("Hello"));
        service.translateAll(java.util.List.of("Hello")); // same text — cache hit

        assertThat(callCount[0]).isEqualTo(1);
    }

    @Test
    void onboarding_flow_uses_translated_questions() {
        var service = new TranslationService(PREFIX, "de");
        var result  = service.translateAll(OnboardingFlow.defaultQuestions());

        assertThat(result.translationFailed()).isFalse();
        assertThat(result.texts()).hasSize(OnboardingFlow.defaultQuestions().size());
        result.texts().forEach(q -> assertThat(q).startsWith("[de] "));

        var flow = new OnboardingFlow(result.texts());
        assertThat(flow.start()).startsWith("[de] ");
        assertThat(flow.totalSteps()).isEqualTo(OnboardingFlow.defaultQuestions().size());
    }

    @Test
    void single_translate_convenience_returns_original_on_failure() {
        var service = new TranslationService(FAILING, "ru");
        assertThat(service.translate("Hello")).isEqualTo("Hello");
    }
}
