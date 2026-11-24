package com.playtranslate.bunpro

import android.content.Context
import com.playtranslate.dictionary.SudachiJapaneseTokenizer

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

        val index = BunproGrammarIndex(
            catalogue,
            extraForms = BunproGrammarStore.loadStructureForms(ctx),
        )
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
