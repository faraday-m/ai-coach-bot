package dev.coachbot.translation;

import java.util.Optional;

/** Pluggable HTTP translation backend. */
@FunctionalInterface
public interface TranslationClient {
    Optional<String> translate(String text, String from, String to);
}
