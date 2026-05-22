package dev.coachbot.transport.telegram;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramMarkdownTest {

    // ── Code fences ───────────────────────────────────────────────────────────

    @Test
    void codeFenceNoLang() {
        String md = "Look:\n```\nint x = 1;\n```\nDone.";
        assertThat(TelegramMarkdown.toHtml(md))
                .isEqualTo("Look:\n<pre><code>int x = 1;</code></pre>\nDone.");
    }

    @Test
    void codeFenceWithLang() {
        String md = "```java\nSystem.out.println(\"hi\");\n```";
        assertThat(TelegramMarkdown.toHtml(md))
                .contains("<pre><code>")
                .contains("System.out.println")
                .doesNotContain("java\n");
    }

    @Test
    void codeFenceEscapesHtmlInsideCode() {
        String md = "```\nif (a < b && c > 0) {}\n```";
        assertThat(TelegramMarkdown.toHtml(md))
                .contains("a &lt; b &amp;&amp; c &gt; 0");
    }

    @Test
    void codeFenceWithRegexAsterisks() {
        // A regex pattern inside a code block — ** must NOT become <b>
        // Using a raw regex without backslashes to keep the test readable
        String md = "Use this pattern:\n```\n**(.+?)**\n```\nIt matches **bold** markers.";
        String html = TelegramMarkdown.toHtml(md);

        // The ** inside the fence stays literal, wrapped in <pre><code>
        assertThat(html).contains("<pre><code>**(.+?)**</code></pre>");
        // The ** outside the fence IS converted to bold
        assertThat(html).contains("<b>bold</b>");
        // No stray <b> tags from the regex inside the fence
        assertThat(html).containsOnlyOnce("<b>");
    }

    @Test
    void codeFenceWithQuantifiers() {
        // *, ?, + and | inside a fence — none should trigger markdown formatting
        String md = "```\n(foo|bar)?(baz)* [a-z]+ .*\n```";
        String html = TelegramMarkdown.toHtml(md);

        assertThat(html).contains("(foo|bar)?(baz)* [a-z]+ .*");
        assertThat(html).doesNotContain("<b>");
        assertThat(html).doesNotContain("<i>");
    }

    @Test
    void inlineCodeWithRegex() {
        // Asterisks inside backtick-code must not become <b>
        String md = "Match with `[a-z*]+` or `a**b` and bold is **here**.";
        String html = TelegramMarkdown.toHtml(md);

        assertThat(html).contains("<code>[a-z*]+</code>");
        assertThat(html).contains("<code>a**b</code>");
        // Bold only from the ** outside backticks
        assertThat(html).contains("<b>here</b>");
        assertThat(html).containsOnlyOnce("<b>");
    }

    @Test
    void multipleFencesInOneMessage() {
        String md = "First:\n```\nfoo()\n```\nSecond:\n```\nbar()\n```";
        String html = TelegramMarkdown.toHtml(md);
        assertThat(html).contains("<pre><code>foo()</code></pre>");
        assertThat(html).contains("<pre><code>bar()</code></pre>");
    }

    // ── Inline code ───────────────────────────────────────────────────────────

    @Test
    void inlineCode() {
        assertThat(TelegramMarkdown.toHtml("Use `ArrayList` here."))
                .isEqualTo("Use <code>ArrayList</code> here.");
    }

    @Test
    void inlineCodeNotAffectedByBoldPattern() {
        // ** inside backticks must not become <b>
        assertThat(TelegramMarkdown.toHtml("The `**raw**` token stays."))
                .contains("<code>**raw**</code>");
    }

    // ── Bold ──────────────────────────────────────────────────────────────────

    @Test
    void boldDoubleAsterisk() {
        assertThat(TelegramMarkdown.toHtml("This is **important**."))
                .isEqualTo("This is <b>important</b>.");
    }

    @Test
    void multipleBoldSpans() {
        assertThat(TelegramMarkdown.toHtml("**A** and **B**"))
                .isEqualTo("<b>A</b> and <b>B</b>");
    }

    // ── Italic ────────────────────────────────────────────────────────────────

    @Test
    void italicUnderscore() {
        assertThat(TelegramMarkdown.toHtml("This is _italic_ text."))
                .isEqualTo("This is <i>italic</i> text.");
    }

    @Test
    void snakeCaseNotConvertedToItalic() {
        // user_id must NOT become user<i>id</i>
        assertThat(TelegramMarkdown.toHtml("Field user_id is required."))
                .doesNotContain("<i>")
                .contains("user_id");
    }

    // ── Headings ──────────────────────────────────────────────────────────────

    @Test
    void headingH1() {
        assertThat(TelegramMarkdown.toHtml("# Virtual Threads"))
                .isEqualTo("<b>Virtual Threads</b>");
    }

    @Test
    void headingH2andH3() {
        String md = "## Key Points\n### Details";
        assertThat(TelegramMarkdown.toHtml(md))
                .isEqualTo("<b>Key Points</b>\n<b>Details</b>");
    }

    // ── HTML escaping in plain text ───────────────────────────────────────────

    @Test
    void escapesAmpersandInPlainText() {
        assertThat(TelegramMarkdown.toHtml("A & B"))
                .isEqualTo("A &amp; B");
    }

    @Test
    void escapesAngledBracketsInPlainText() {
        assertThat(TelegramMarkdown.toHtml("if (x < 10 && y > 0)"))
                .isEqualTo("if (x &lt; 10 &amp;&amp; y &gt; 0)");
    }

    @Test
    void htmlInsideCodeFenceEscaped_butOutsideAlso() {
        String md = "Use `x < y` or **x > y**.";
        String html = TelegramMarkdown.toHtml(md);
        // inline code: escaped inside <code>
        assertThat(html).contains("<code>x &lt; y</code>");
        // bold text: escaped then bolded
        assertThat(html).contains("<b>x &gt; y</b>");
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    void nullReturnsEmpty() {
        assertThat(TelegramMarkdown.toHtml(null)).isEmpty();
    }

    @Test
    void emptyStringReturnsEmpty() {
        assertThat(TelegramMarkdown.toHtml("")).isEmpty();
    }

    @Test
    void plainTextPassesThrough() {
        assertThat(TelegramMarkdown.toHtml("Hello world!"))
                .isEqualTo("Hello world!");
    }
}
