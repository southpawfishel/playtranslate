package com.playtranslate.bunpro

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One grammar point's detail page:
 * `GET https://bunpro.jp/_next/data/<buildId>/en/grammar_points/<slug>.json?slug=<slug>`
 *
 * **Only a fraction of the response is modelled here, deliberately.** The raw
 * payload also carries the site's entire i18n bundle (`__namespaces`) on every
 * page, forum replies, and the full teaching write-up. Those are dropped at
 * parse time by `ignoreUnknownKeys`:
 *
 *  - `__namespaces` is the bulk of the bytes and is pure duplication.
 *  - `latestDiscourseReplies` is other users' forum content.
 *  - `writeups[].body` is Bunpro's actual copyrighted lesson prose. Fetching
 *    it for a point the user opened is one thing; harvesting all ~979 into a
 *    local corpus is another, and this app does not do the second.
 */
@Serializable
data class BunproGrammarDetailResponse(
    val pageProps: BunproGrammarDetailProps = BunproGrammarDetailProps(),
)

@Serializable
data class BunproGrammarDetailProps(
    val reviewable: BunproGrammarDetail? = null,
    val included: BunproGrammarIncluded = BunproGrammarIncluded(),
)

@Serializable
data class BunproGrammarDetail(
    val id: Long,
    val title: String,
    val slug: String? = null,
    val meaning: String? = null,
    val level: String? = null,
    @SerialName("nuance_translation") val nuanceTranslation: String? = null,
    val caution: String? = null,
    @SerialName("part_of_speech_translation") val partOfSpeech: String? = null,
    @SerialName("register_translation") val register: String? = null,
    /** Conjugation tables as HTML, e.g.
     *  `Past Form: <strong>よかった</strong><br>…`. See [structureForms]. */
    @SerialName("polite_structure") val politeStructure: String? = null,
    @SerialName("casual_structure") val casualStructure: String? = null,
) {
    /**
     * The conjugated surface forms named in the structure tables — the
     * `<strong>`-wrapped values.
     *
     * This is the payoff of fetching detail at all: the catalogue index lists
     * いい / 良い / よい, but only the structure tables name よくない, よかった and
     * よくなかった. Without them a lexical matcher misses every conjugated
     * occurrence.
     */
    fun structureForms(): List<String> =
        listOfNotNull(politeStructure, casualStructure)
            .flatMap { STRONG.findAll(it).map { m -> m.groupValues[1] }.toList() }
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.all(::isJapanese) }
            .distinct()

    private fun isJapanese(c: Char): Boolean =
        c in '぀'..'ゟ' || c in '゠'..'ヿ' || c in '一'..'鿿'

    private companion object {
        val STRONG = Regex("<strong>(.*?)</strong>", RegexOption.DOT_MATCHES_ALL)
    }
}

@Serializable
data class BunproGrammarIncluded(
    val studyQuestions: List<BunproStudyQuestion> = emptyList(),
    /**
     * The lesson write-up for this point.
     *
     * Modelled, but deliberately NOT persisted by the bulk sweep: this is
     * Bunpro's teaching content, and mirroring all ~979 of them would be
     * building a local copy of the product. It is fetched on demand for the
     * one point the user opened — the same thing their browser does when they
     * visit that page — and that single response is what gets displayed.
     */
    val writeups: List<BunproWriteup> = emptyList(),
)

@Serializable
data class BunproWriteup(
    val id: Long = 0,
    /** HTML. Carries `<span data-gp-id>` cross-references and
     *  `<li data-study-question>` example placeholders that render as empty
     *  list items once tags are stripped — see `plainText`. */
    val body: String? = null,
) {
    /** The write-up as readable text — see [BunproHtml], which every
     *  Bunpro string goes through, not just this one. */
    fun plainText(): String = BunproHtml.toPlainText(body)
}

/**
 * An example sentence. [answer] / [kanjiAnswer] are additional real surface
 * forms for the point.
 *
 * NOTE the two sibling fields that are NOT modelled: `wrong_answers` holds
 * deliberately INCORRECT Japanese (いいない, いくない, よかったない …) used to
 * generate teaching feedback. Indexing those would make the matcher fire on
 * malformed text and label it valid grammar. They must never be treated as
 * surface forms — which is exactly why this class does not expose them.
 */
@Serializable
data class BunproStudyQuestion(
    val id: Long,
    /** Cloze text with `____` where the answer belongs, plus `gp-popout`
     *  spans cross-referencing other grammar points. */
    val content: String? = null,
    val answer: String? = null,
    @SerialName("kanji_answer") val kanjiAnswer: String? = null,
    val translation: String? = null,
    val tense: String? = null,
    @SerialName("question_type") val questionType: String? = null,
    @SerialName("female_audio_url") val femaleAudioUrl: String? = null,
    @SerialName("male_audio_url") val maleAudioUrl: String? = null,
)
