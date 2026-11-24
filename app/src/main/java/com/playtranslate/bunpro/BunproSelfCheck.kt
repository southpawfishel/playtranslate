package com.playtranslate.bunpro

import android.content.Context
import com.playtranslate.dictionary.SudachiJapaneseTokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device verification for the parts that cannot be tested in a JVM unit test.
 *
 * The matcher's whole design rests on one unverified assumption: that Sudachi
 * lemmatizes conjugated forms onto the catalogue's dictionary forms (よかった →
 * いい), which is what lets the lemma arm find grammar the index's aliases miss.
 * Sudachi needs a packaged `.dic` from a downloaded language pack, so that can
 * only be checked on a real install.
 *
 * This runs the check and returns a human-readable report, so verification
 * needs nothing but the app itself — no adb, no logcat.
 */
object BunproSelfCheck {

    /**
     * Surface → dictionary forms that count as a pass.
     *
     * A SET, not a single string: verified on-device, Sudachi lemmatizes
     * よかった to よい, not いい. Both are the same adjective (よい is the
     * literary form, いい the colloquial one) and Bunpro lists both as aliases
     * for the point, so either resolves correctly. An earlier version of this
     * check expected いい alone and reported a false failure.
     */
    private val LEMMA_CASES = listOf(
        "よかった" to setOf("いい", "よい", "良い"),  // い-adjective past — headline case
        "よくない" to setOf("いい", "よい", "良い"),  // い-adjective negative
        "食べた" to setOf("食べる"),                 // ichidan past
        "行かない" to setOf("行く"),                 // godan negative
        "見ている" to setOf("見る"),                 // te-iru
    )

    /** Sentence → the start of the title of a point that must be found in
     *  it. Each was a measured miss before the sequence arm existed. */
    private val SEQUENCE_CASES = listOf(
        "食べてしまった" to "てしまう",
        "買っておいた" to "ておく",
        "行くことができました" to "ことができる",
        "彼は学生であり" to "である",
        "見てもらいました" to "てもらう",
    )

    data class Line(val ok: Boolean?, val text: String)

    data class Report(val lines: List<Line>) {
        val summary: String
            get() {
                val checked = lines.filter { it.ok != null }
                val passed = checked.count { it.ok == true }
                return when {
                    checked.isEmpty() -> "Could not run — see details"
                    passed == checked.size -> "All $passed checks passed"
                    else -> "$passed of ${checked.size} checks passed"
                }
            }

        fun asText(): String = lines.joinToString("\n") { l ->
            when (l.ok) {
                true -> "✓ ${l.text}"
                false -> "✗ ${l.text}"
                null -> l.text
            }
        }
    }

    /**
     * Tokenizes known conjugated forms and reports whether each lemmatizes to
     * the expected dictionary form, then whether the stored catalogue can
     * actually match a sentence built from one.
     */
    suspend fun run(ctx: Context): Report {
        val lines = mutableListOf<Line>()

        // ── 1. Tokenizer availability ───────────────────────────────────
        val probe = try {
            SudachiJapaneseTokenizer.Provider.analyze("よかった")
        } catch (e: Exception) {
            emptyList()
        }
        if (probe.isEmpty()) {
            lines += Line(
                null,
                "Tokenizer unavailable. Install the Japanese language pack and " +
                    "set the source language to Japanese, then run this again.",
            )
            return Report(lines)
        }
        lines += Line(null, "Tokenizer: OK")

        // ── 2. The load-bearing assumption ──────────────────────────────
        lines += Line(null, "\nLemmas (surface → dictionary form):")
        for ((surface, accepted) in LEMMA_CASES) {
            val tokens = try {
                SudachiJapaneseTokenizer.Provider.analyze(surface)
            } catch (e: Exception) {
                emptyList()
            }
            val got = tokens.firstOrNull()?.dictionaryForm
            val ok = got != null && got in accepted
            lines += Line(
                ok,
                "$surface → ${got ?: "(none)"}" +
                    if (ok) "" else "  expected one of ${accepted.joinToString("/")}",
            )
        }

        // ── 3. End-to-end against the stored catalogue ──────────────────
        val catalogue = BunproGrammarStore.loadIndex(ctx)
        if (catalogue.isEmpty()) {
            lines += Line(null, "\nCatalogue: not synced yet — sync it to test matching.")
            return Report(lines)
        }
        lines += Line(null, "\nCatalogue: ${catalogue.size} grammar points")

        // Built the way the app builds it — every form analysed once — and
        // timed, because that analysis is the one cost here that only a real
        // device can measure.
        val extra = BunproGrammarStore.loadStructureForms(ctx)
        val analyse = { s: String ->
            try {
                SudachiJapaneseTokenizer.Provider.analyze(s)
            } catch (e: Exception) {
                emptyList()
            }
        }
        val started = System.nanoTime()
        val index = withContext(Dispatchers.Default) {
            BunproGrammarIndex(catalogue, extraForms = extra, tokenize = analyse)
        }
        val buildMs = (System.nanoTime() - started) / 1_000_000
        lines += Line(null, "Index build: $buildMs ms (once per app start)")

        // ── 4. Conjugated patterns ──────────────────────────────────────
        // The sequence arm: a pattern listed in dictionary form, found with
        // its last word conjugated. Depends on Sudachi splitting the FORM
        // the same way it splits the sentence, which is the device's call.
        lines += Line(null, "\nConjugated patterns:")
        for ((text, expected) in SEQUENCE_CASES) {
            val found = index.match(text, analyse(text))
                .flatMap { m -> m.candidates.map { it.title.trim() } }
            val ok = found.any { it.startsWith(expected) }
            lines += Line(
                ok,
                "$text → $expected" +
                    if (ok) "" else "  (found: ${found.distinct().joinToString("、").ifEmpty { "nothing" }})",
            )
        }

        val sentence = "昨日はよかった。"
        val tokens = try {
            SudachiJapaneseTokenizer.Provider.analyze(sentence)
        } catch (e: Exception) {
            emptyList()
        }
        val viaLemma = index.match(sentence, tokens)
            .any { it.via == GrammarMatch.Via.LEMMA }
        lines += Line(
            viaLemma,
            "「$sentence」 matched a grammar point via lemma" +
                if (viaLemma) "" else " — the lemma arm found nothing here",
        )

        // Report WHAT matched, not how many. Counts hide the actual gain:
        // without lemmas the surface arm matches fragments (った, from the
        // う-Verb Past point) which the whole-word lemma hit then displaces.
        // Identical totals, completely different quality.
        fun describe(ms: List<GrammarMatch>) =
            if (ms.isEmpty()) "(none)"
            else ms.joinToString(", ") { "${it.matchedForm}→${it.primary.title}" }

        lines += Line(null, "\nWithout lemmas: " + describe(index.match(sentence, tokens = emptyList())))
        lines += Line(null, "With lemmas:    " + describe(index.match(sentence, tokens)))
        return Report(lines)
    }
}
