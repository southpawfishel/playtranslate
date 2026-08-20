package com.playtranslate.bunpro

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The Bunpro grammar-point catalogue, as served by the Next.js data route
 * behind `bunpro.jp/grammar_points`:
 *
 * `GET https://bunpro.jp/_next/data/<buildId>/en/grammar_points.json`
 *
 * One request returns every point (~979 as of 2026-08) with no pagination.
 *
 * **The `<buildId>` is deploy-scoped** — it changes whenever Bunpro ships, so
 * it must be read at runtime from `__NEXT_DATA__` on any bunpro.jp page rather
 * than hardcoded. A stale id 404s.
 *
 * Note what this payload does NOT contain: the explanatory prose, examples, and
 * structure HTML that make up Bunpro's actual teaching content. This is an
 * index — title, short gloss, and search aliases. Caching it is a much lighter
 * footprint than mirroring the lessons themselves.
 */
@Serializable
data class BunproGrammarIndexResponse(
    val pageProps: BunproGrammarPageProps = BunproGrammarPageProps(),
)

@Serializable
data class BunproGrammarPageProps(
    val grammarPoints: List<BunproGrammarPoint> = emptyList(),
)

@Serializable
data class BunproGrammarPoint(
    val id: Long,
    val title: String,
    val slug: String? = null,
    val furigana: String? = null,
    val meaning: String? = null,
    /** "JLPT5".."JLPT1", "Non-JLPT", "関西弁". Drives how prominently a
     *  detected point is surfaced relative to the user's own progress. */
    val level: String? = null,
    /** Catalogue-wide ordering, roughly ascending difficulty. */
    @SerialName("grammar_order") val grammarOrder: Int? = null,
    /**
     * Comma-separated search keywords. Mixes three different things:
     * matchable Japanese surface forms (`と言う事`, `ってこと`), romaji
     * (`toiukoto`), and descriptive tags (`nominalizer`, `particle`,
     * `case-marking particle`). Only the first group is usable for matching —
     * see [japaneseAliases].
     */
    val metadata: String? = null,
    @SerialName("lesson_id") val lessonId: Int? = null,
) {
    /**
     * The [metadata] entries that are matchable Japanese surface forms: tokens
     * written *entirely* in kana/kanji.
     *
     * The all-Japanese test is what discards the descriptive tags. Crucially it
     * also drops the hybrid grammar terminology — `い-adjective`, `う-verb`,
     * `な-adjective` — which would otherwise register as a hit on the bare
     * kana every time it appeared in a sentence.
     */
    fun japaneseAliases(): List<String> =
        ((metadata ?: "").split(',') + title)
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.all(::isJapanese) }
            .distinct()

    private fun isJapanese(c: Char): Boolean =
        c in '぀'..'ゟ' ||   // hiragana
            c in '゠'..'ヿ' || // katakana (incl. ー)
            c in '一'..'鿿'    // CJK ideographs
}
