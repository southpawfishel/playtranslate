package com.playtranslate.bunpro

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.playtranslate.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.playtranslate.language.SourceLangId

/**
 * "Is this word in my Bunpro reviews?" — the badge's entry point.
 *
 * Session-scoped and in-memory ONLY: nothing survives process death. That is
 * deliberate. SRS standing changes whenever the user studies, so a disk cache
 * of it would serve confidently-wrong badges; the durable half of the design
 * (word identity + "Bunpro doesn't have this word") is deferred until real hit
 * rates and the bulk `/reviews` sync are known. See
 * `docs/features/bunpro-integration.md`.
 *
 * The cache stores misses as well as hits — most words on a screen aren't
 * Bunpro vocab at all, and re-rendering the same capture must not re-ask.
 */
object BunproLookup {

    /** What the badge needs about one word. [srs] is [BunproSrsStatus.UNSTUDIED]
     *  when Bunpro has the word but the user hasn't studied it. */
    data class WordStatus(
        val vocabId: Long,
        val slug: String?,
        val jmdictId: Long?,
        val srs: BunproSrsStatus,
        /** Display fields, carried so near-miss candidates can be listed to
         *  the user without a second round trip. */
        val title: String? = null,
        val kana: String? = null,
        val meaning: String? = null,
    )

    /**
     * Result of asking about one word. [Absent] and [Unavailable] MUST stay
     * distinct: the badge labels a word "not in Bunpro", and that claim is only
     * honest when a search actually came back without a match. Collapsing a
     * failed or unauthorized call into the same value would assert absence
     * every time the token expires or the network drops.
     */
    sealed interface Outcome {
        /** Bunpro has this word; [status] carries the SRS standing, which may
         *  be [BunproSrsStatus.UNSTUDIED]. */
        data class Found(val status: WordStatus) : Outcome
        /** The search succeeded and returned NOTHING — Bunpro genuinely has no
         *  vocab for this word. The only state that justifies telling the user
         *  the word isn't in Bunpro. */
        data object Absent : Outcome

        /**
         * The search returned results, but none was an exact surface match.
         *
         * Bunpro's search is deliberately tolerant — it matches substrings,
         * romaji, and English glosses (a query of `ということ` comes back with
         * `と言うことは`). So a non-exact hit means we genuinely don't know:
         * it may be the same word under a headword we didn't anticipate, or an
         * unrelated entry that merely shares a gloss. Claiming either "you've
         * studied this" or "not in Bunpro" would be a guess, so this renders
         * nothing.
         */
        data class Inconclusive(val candidates: List<WordStatus>) : Outcome
        /** We couldn't ask: feature off, no token, wrong source language,
         *  expired token, or a network/parse failure. Render nothing. */
        data object Unavailable : Outcome
    }

    private const val CAPACITY = 500

    /** Wrapper so a cached answer is distinguishable from "never asked" —
     *  [LinkedHashMap] can't tell those apart by value. Only ever holds a
     *  RESOLVED outcome (never [Outcome.Unavailable]): a call we couldn't make
     *  established nothing worth remembering. */
    private class Holder(val outcome: Outcome)

    private val lru = object : LinkedHashMap<String, Holder>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Holder>?): Boolean =
            size > CAPACITY
    }

    @Synchronized
    private fun cached(word: String): Holder? = lru[word]

    @Synchronized
    private fun store(word: String, outcome: Outcome) {
        lru[word] = Holder(outcome)
    }

    /** Drops everything. Called when the token or enable flag changes — the
     *  cached answers belong to the old credential. */
    @Synchronized
    fun clear() = lru.clear()

    /**
     * Bumped after a successful add so open surfaces can re-render. Mirrors
     * [com.playtranslate.AnkiManager.noteAddedTick], which the word sheet and
     * results list already collect for the deck badge.
     */
    private val _reviewAddedTick = MutableStateFlow(0)
    val reviewAddedTick: StateFlow<Int> = _reviewAddedTick.asStateFlow()

    /**
     * Records a just-created review for [word] and signals watchers.
     *
     * Rewrites the cache entry in place rather than dropping it: the word was
     * cached moments ago as "not studied", and without this the pill would keep
     * serving that stale answer for the rest of the session — the add would
     * look like it did nothing.
     */
    @Synchronized
    fun recordAdded(word: String, review: BunproReview) {
        val previous = (lru[word]?.outcome as? Outcome.Found)?.status ?: return
        lru[word] = Holder(
            Outcome.Found(previous.copy(srs = BunproSrsStatus.from(review)))
        )
        _reviewAddedTick.update { it + 1 }
    }

    /**
     * True when a lookup could produce a meaningful answer: the user switched
     * Bunpro on, saved a token, AND is translating Japanese. The language gate
     * matters now that absence is labelled — on a Chinese or Korean source
     * every word is trivially "not in Bunpro", which is noise, and asking
     * costs a request per word.
     */
    fun isEnabled(prefs: Prefs): Boolean =
        prefs.bunproEnabled &&
            prefs.bunproToken.isNotBlank() &&
            prefs.sourceLangId == SourceLangId.JA

    /**
     * Bunpro's standing for [word]. Safe to call from any dispatcher —
     * [BunproClient] confines its own IO.
     *
     * A 401 flips `Prefs.bunproTokenRejected` (what surfaces the "token
     * expired" state in Settings) and yields [Outcome.Unavailable] — never
     * [Outcome.Absent]. The word's absence was never established, only our
     * ability to ask, and it is deliberately NOT cached for the same reason.
     */
    suspend fun outcomeFor(ctx: Context, word: String): Outcome {
        if (word.isBlank()) return Outcome.Unavailable
        val prefs = Prefs(ctx.applicationContext)
        if (!isEnabled(prefs)) return Outcome.Unavailable

        cached(word)?.let { return it.outcome }

        return when (val result = BunproClient.searchVocab(prefs.bunproToken, word)) {
            is BunproResult.Ok -> {
                val outcome = resolve(result.value, word)
                store(word, outcome)         // caches absence too — a real answer
                outcome
            }
            BunproResult.Unauthorized -> {
                prefs.bunproTokenRejected = true
                Outcome.Unavailable
            }
            BunproResult.Failed -> Outcome.Unavailable
        }
    }

    /**
     * Adds [status] to the user's Bunpro reviews and updates the cache so the
     * pill flips to studied. Returns true on success.
     *
     * A 401 flips `Prefs.bunproTokenRejected` exactly as the reads do — an
     * expired token must surface as "token expired", not as a failed add.
     */
    suspend fun addToReviews(ctx: Context, word: String, status: WordStatus): Boolean {
        val prefs = Prefs(ctx.applicationContext)
        if (!isEnabled(prefs)) return false
        return when (val result = BunproClient.addToReviews(prefs.bunproToken, status.vocabId)) {
            is BunproResult.Ok -> {
                recordAdded(word, result.value)
                true
            }
            BunproResult.Unauthorized -> {
                prefs.bunproTokenRejected = true
                false
            }
            BunproResult.Failed -> false
        }
    }

    /**
     * Classifies a successful search. The three-way split matters because
     * Bunpro's search is tolerant: an empty result set is real evidence of
     * absence, whereas a non-empty set with no exact hit is evidence of
     * nothing — see [Outcome.Inconclusive].
     */
    @VisibleForTesting
    internal fun resolve(section: BunproSection, word: String): Outcome {
        match(section, word)?.let { return Outcome.Found(it) }
        return if (section.data.isEmpty()) Outcome.Absent
        else Outcome.Inconclusive(candidates(section))
    }

    /**
     * Picks the entry in [section] that actually IS [word], or null.
     *
     * Search is fuzzy — it matches on meaning and accepts romaji — so the
     * top hit for a word can be an unrelated entry that merely shares a
     * gloss. A badge asserting "you've studied this" about the wrong word is
     * worse than no badge, so only an exact surface match on the written
     * form, the slug, or the kana counts. Anything less, we show nothing.
     */
    private fun match(section: BunproSection, word: String): WordStatus? =
        section.data.firstOrNull { candidate ->
            val a = candidate.attributes
            a.title == word || a.slug == word || a.kana == word
        }?.let { section.toStatus(it) }

    /** Every returned entry as a [WordStatus] — the near-miss candidates shown
     *  behind the "maybe in Bunpro" pill. */
    private fun candidates(section: BunproSection): List<WordStatus> =
        section.data.map { section.toStatus(it) }

    private fun BunproSection.toStatus(item: BunproItem) = WordStatus(
        vocabId = item.attributes.id,
        slug = item.attributes.slug,
        jmdictId = item.attributes.jmdictId,
        srs = srsFor(item),
        title = item.attributes.title,
        kana = item.attributes.kana,
        meaning = item.attributes.meaning,
    )
}
