package com.playtranslate.bunpro

/**
 * Turns Bunpro's HTML into readable text.
 *
 * Every human-facing string Bunpro returns is HTML, not plain text — the
 * write-up body, but equally `nuance_translation`, `caution` and the
 * catalogue's `meaning`. They carry `<strong>` emphasis, `<span data-gp-id>`
 * cross-references to other grammar points, and HTML entities. Only the
 * write-up was being cleaned, which is why raw `<strong>` tags showed up in
 * the short fields; this exists so there is ONE answer to "how do we render
 * Bunpro text" rather than one per field.
 *
 * Emphasis is dropped rather than converted to spans. The write-up is stored
 * as text, so preserving styling there would mean storing markup and changing
 * the cache format; dropping it everywhere keeps the short fields and the long
 * one looking like the same thing, which is the point.
 */
object BunproHtml {

    /**
     * [src] as readable text: no tags, no entities, no stray blank lines.
     *
     * Two behaviours worth knowing, both of which showed up as visibly broken
     * paragraphs before they were handled:
     *
     *  - **Example placeholders are dropped whole.** `<li data-study-question>`
     *    carries no text of its own; removing only the opening tag left the
     *    empty `</li>` and its surrounding list behind, which is where the run
     *    of blank lines mid-prose came from.
     *  - **Source newlines are not line breaks.** Bodies are indented HTML, so
     *    stripping tags leaves the author's formatting newlines inside every
     *    paragraph — rendered verbatim they break sentences at arbitrary
     *    points. Only `<br>`, `</li>` and block ends are real breaks; every
     *    other run of whitespace collapses to a single space.
     *
     * Returns "" for null/blank input, so callers can treat empty as absent.
     */
    fun toPlainText(src: String?): String {
        if (src.isNullOrBlank()) return ""
        return src
            .replace(STUDY_QUESTION_LI, "")
            .replace(BR, LINE.toString())
            .replace(LIST_ITEM_END, LINE.toString())
            .replace(BLOCK_END, PARA.toString())
            .replace(TAG, "")
            .decodeEntities()
            .replace(WHITESPACE, " ")
            .split(PARA).joinToString("\n\n") { para ->
                para.split(LINE).map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            }
            .replace(BLANK_RUN, "\n\n")
            .trim()
    }

    /** [toPlainText], or null when there is nothing left to show — the shape
     *  the UI's optional-line binders want. */
    fun toPlainTextOrNull(src: String?): String? = toPlainText(src).takeIf { it.isNotEmpty() }

    /** `&amp;` is decoded LAST so a literal `&amp;lt;` in the source doesn't
     *  come out as `<`. */
    private fun String.decodeEntities(): String =
        replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&#x27;", "'").replace("&apos;", "'")
            .replace("&rsquo;", "’").replace("&lsquo;", "‘")
            .replace("&ldquo;", "“").replace("&rdquo;", "”")
            .replace("&hellip;", "…")
            .replace("&mdash;", "—").replace("&ndash;", "–")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&amp;", "&")

    /** Break the author asked for (`<br>`, end of a list item). */
    private const val LINE = '\u0001'
    /** Block boundary — becomes a blank line. */
    private const val PARA = '\u0002'

    private val STUDY_QUESTION_LI = Regex(
        "<li[^>]*data-study-question[^>]*>.*?</li>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val LIST_ITEM_END = Regex("</li>", RegexOption.IGNORE_CASE)
    private val BLOCK_END = Regex("</(p|div|ul|ol|section|article|h[1-6])>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]+>")
    /** Deliberately excludes the two sentinels above. */
    private val WHITESPACE = Regex("[ \\t\\r\\n\\u00a0\\u3000]+")
    private val BLANK_RUN = Regex("\n{3,}")
}
