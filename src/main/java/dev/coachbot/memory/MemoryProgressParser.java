package dev.coachbot.memory;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the {@code ## Topic Statistics} Markdown table from a memory document
 * and returns structured {@link TopicProgress} records.
 *
 * <p>Expected table format (produced by the memory-update prompt):
 * <pre>
 * ## Topic Statistics
 * | Topic | Sessions | Last seen | Level |
 * |-------|----------|-----------|-------|
 * | Reactive Streams | 5 | 2026-01-15 | ⬆️ Confident |
 * </pre>
 *
 * <p>This parser is intentionally lenient: extra whitespace, missing trailing pipe,
 * and additional columns are all tolerated.
 */
public class MemoryProgressParser {

    private MemoryProgressParser() {}

    // ── Data ───────────────────────────────────────────────────────────────────

    /**
     * A single row from the Topic Statistics table.
     *
     * @param fraction 0.0–1.0 value suitable for a {@code ProgressBar}:
     *                 ⬇️ = 0.2, ➡️ = 0.6, ⬆️ = 1.0
     */
    public record TopicProgress(String topic, int sessions, String lastSeen, String level) {

        /** 0.0–1.0 fraction for a progress bar. */
        public double fraction() {
            if (level == null) return 0;
            if (level.contains("⬆") || level.contains("Confident")) return 1.0;
            if (level.contains("➡") || level.contains("Familiar"))  return 0.6;
            return 0.2; // ⬇️ Needs work or unknown
        }

        /** Single emoji representing the level. */
        public String emoji() {
            if (level == null) return "❓";
            if (level.contains("⬆") || level.contains("Confident")) return "⬆️";
            if (level.contains("➡") || level.contains("Familiar"))  return "➡️";
            return "⬇️";
        }
    }

    // ── Parsing ────────────────────────────────────────────────────────────────

    /**
     * Parses all topic rows from the {@code ## Topic Statistics} section.
     * Returns an empty list if the section is absent or has no data rows.
     */
    public static List<TopicProgress> parse(String memoryDocument) {
        var result = new ArrayList<TopicProgress>();
        if (memoryDocument == null || memoryDocument.isBlank()) return result;

        boolean inSection = false;

        for (String line : memoryDocument.lines().toList()) {
            String t = line.trim();

            if (t.startsWith("## Topic Statistics")) {
                inSection = true;
                continue;
            }
            if (inSection && t.startsWith("## ")) break; // start of next section

            if (!inSection || !t.startsWith("|")) continue;

            // Skip the header row and separator row
            if ((t.contains("Topic") && t.contains("Sessions")) ||
                t.replaceAll("[|:\\-\\s]", "").isBlank()) continue;

            String[] cols = t.split("\\|", -1);
            // Expected split on "|topic|sessions|lastSeen|level|":
            //   cols[0]="" cols[1]=topic cols[2]=sessions cols[3]=lastSeen cols[4]=level …
            if (cols.length < 5) continue;
            try {
                String topic    = cols[1].trim();
                int    sessions = Integer.parseInt(cols[2].trim());
                String lastSeen = cols[3].trim();
                String level    = cols[4].trim();
                if (!topic.isBlank()) {
                    result.add(new TopicProgress(topic, sessions, lastSeen, level));
                }
            } catch (NumberFormatException ignored) {}
        }
        return result;
    }

    // ── Formatting ─────────────────────────────────────────────────────────────

    /**
     * Formats the topic list as a text progress summary for delivery via chat.
     * Uses Unicode block characters (█ / ░) for a simple visual progress bar.
     *
     * <p>Example output:
     * <pre>
     * 📊 *Learning Progress*
     *
     * ⬆️ *Reactive Streams*
     *    ██████████  ⬆️ Confident — 5 sessions, last: 2026-01-15
     * </pre>
     */
    public static String formatAsText(List<TopicProgress> topics) {
        if (topics.isEmpty()) return "No topics tracked yet.";
        var sb = new StringBuilder("📊 *Learning Progress*\n\n");
        for (TopicProgress t : topics) {
            int    filled = (int) Math.round(t.fraction() * 10);
            String bar    = "█".repeat(filled) + "░".repeat(10 - filled);
            sb.append(t.emoji()).append(" *").append(t.topic()).append("*\n");
            sb.append("   ").append(bar)
              .append("  ").append(t.level())
              .append(" — ").append(t.sessions())
              .append(t.sessions() == 1 ? " session" : " sessions")
              .append(", last: ").append(t.lastSeen())
              .append("\n\n");
        }
        return sb.toString().stripTrailing();
    }
}
