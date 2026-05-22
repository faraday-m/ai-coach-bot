package dev.coachbot.transport.telegram;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts LLM-generated Markdown to Telegram HTML.
 *
 * <h2>Why HTML, not MarkdownV2?</h2>
 * <p>Telegram's MarkdownV2 requires every reserved character outside formatting marks
 * to be backslash-escaped ({@code . - ( ) ! = | { } # + - ~} etc.). LLM output contains
 * all of these freely, so a reliable escaper would need a full parser.
 * HTML mode only requires {@code & < >} to be escaped, which is trivial.
 *
 * <h2>What is converted</h2>
 * <ul>
 *   <li>Code fences &#96;&#96;&#96;lang\ncode\n&#96;&#96;&#96; → {@code <pre><code>…</code></pre>}</li>
 *   <li>Inline code &#96;code&#96; → {@code <code>…</code>}</li>
 *   <li>Bold {@code **text**} → {@code <b>text</b>}</li>
 *   <li>Italic {@code _text_} → {@code <i>text</i>} (single underscore, not inside words)</li>
 *   <li>Headings {@code # H1}, {@code ## H2}, {@code ### H3} → {@code <b>…</b>}</li>
 *   <li>Plain text: {@code &}, {@code <}, {@code >} are HTML-escaped</li>
 * </ul>
 *
 * <p>Intentionally skipped: links, tables, block quotes — rarely useful in a chat context
 * and error-prone to convert reliably from LLM output.
 */
class TelegramMarkdown {

    // ── Patterns ───────────────────────────────────────────────────────────────

    /** Matches fenced code blocks: ``` optionalLang newline … ``` */
    private static final Pattern CODE_FENCE = Pattern.compile(
            "```(?:[a-zA-Z0-9+#-]*\\n)?([\\s\\S]*?)```",
            Pattern.MULTILINE);

    /** Matches inline code: `text` — no newlines inside. */
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`\\n]+)`");

    /** Matches **bold** — non-greedy, no newlines. */
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");

    /** Matches _italic_ where the underscore is NOT inside a word (avoids snake_case). */
    private static final Pattern ITALIC = Pattern.compile(
            "(?<![\\w])_([^_\\n]+)_(?![\\w])");

    /** Matches heading lines: # Title, ## Title, ### Title — captures the title text. */
    private static final Pattern HEADING = Pattern.compile(
            "(?m)^#{1,3} (.+)$");

    /**
     * Placeholder used to hide inline-code spans from bold/italic patterns.
     * SOH (U+0001) never appears in LLM output.
     */
    private static final char SLOT_SEP = '';

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Converts LLM-generated Markdown text to Telegram-compatible HTML.
     *
     * <p>Safe to call with {@code null} — returns an empty string.
     */
    static String toHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";

        StringBuilder result = new StringBuilder();
        Matcher fence = CODE_FENCE.matcher(markdown);
        int last = 0;

        while (fence.find()) {
            // Plain text before this code fence — apply inline conversions
            result.append(convertInline(markdown.substring(last, fence.start())));
            // Code fence — escape HTML inside the code, wrap in <pre><code>
            String code = fence.group(1).stripTrailing();
            result.append("<pre><code>").append(escapeHtml(code)).append("</code></pre>");
            last = fence.end();
        }
        // Remaining plain text after the last fence
        result.append(convertInline(markdown.substring(last)));

        return result.toString();
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    /**
     * Processes a segment of text that is NOT inside a code fence:
     * HTML-escapes special chars, then applies bold/italic/heading conversions.
     *
     * <p>Inline code spans are extracted into numbered placeholders before bold/italic
     * processing so that e.g. {@code `**raw**`} is not accidentally bolded.
     * Placeholders use {@code U+0001} as a delimiter — a control char absent from LLM output.
     */
    private static String convertInline(String text) {
        // 1. HTML-escape first so < > & in plain text become entities.
        //    Backticks are not HTML-special, so inline-code delimiters survive intact.
        text = escapeHtml(text);

        // 2. Extract inline code into SOH-delimited placeholders.
        //    This prevents bold/italic regexes from acting on code contents.
        List<String> codeSlots = new ArrayList<>();
        Matcher codeMatcher = INLINE_CODE.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (codeMatcher.find()) {
            // Store the final <code>…</code> HTML in the slot list
            codeSlots.add("<code>" + codeMatcher.group(1) + "</code>");
            // Replace the matched span with a placeholder that cannot match any formatting pattern
            String placeholder = SLOT_SEP + String.valueOf(codeSlots.size() - 1) + SLOT_SEP;
            codeMatcher.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
        }
        codeMatcher.appendTail(sb);
        text = sb.toString();

        // 3. Bold (**text**)
        text = BOLD.matcher(text).replaceAll("<b>$1</b>");

        // 4. Italic (_text_) — skips snake_case identifiers
        text = ITALIC.matcher(text).replaceAll("<i>$1</i>");

        // 5. Headings (# Title → bold on its own line)
        text = HEADING.matcher(text).replaceAll("<b>$1</b>");

        // 6. Restore inline code placeholders
        for (int i = 0; i < codeSlots.size(); i++) {
            text = text.replace(SLOT_SEP + String.valueOf(i) + SLOT_SEP, codeSlots.get(i));
        }

        return text;
    }

    private static String escapeHtml(String text) {
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
