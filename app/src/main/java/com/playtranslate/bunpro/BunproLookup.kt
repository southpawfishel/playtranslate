package com.playtranslate.bunpro

import android.content.Context
import com.playtranslate.Prefs

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
    )

    private const val CAPACITY = 500

    /** Wrapper so a cached "not in Bunpro" (null) is distinguishable from
     *  "never asked" — [LinkedHashMap] can't tell those apart by value. */
    private class Holder(val value: WordStatus?)

    private val lru = object : LinkedHashMap<String, Holder>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Holder>?): Boolean =
            size > CAPACITY
    }

    @Synchronized
    private fun cached(word: String): Holder? = lru[word]

    @Synchronized
    private fun store(word: String, value: WordStatus?) {
        lru[word] = Holder(value)
    }

    /** Drops everything. Called when the token or enable flag changes — the
     *  cached answers belong to the old credential. */
    @Synchronized
    fun clear() = lru.clear()

    /** True when the user has both switched Bunpro on and saved a token. */
    fun isEnabled(prefs: Prefs): Boolean =
        prefs.bunproEnabled && prefs.bunproToken.isNotBlank()

    /**
     * Bunpro's standing for [word], or null when Bunpro doesn't have it, the
     * feature is off, or the call failed. Safe to call from any dispatcher —
     * [BunproClient] confines its own IO.
     *
     * A 401 flips `Prefs.bunproTokenRejected`, which is what surfaces the
     * "token expired" state in Settings. It is NOT cached: the word's absence
     * was never established, only our ability to ask.
     */
    suspend fun statusFor(ctx: Context, word: String): WordStatus? {
        if (word.isBlank()) return null
        val prefs = Prefs(ctx.applicationContext)
        if (!isEnabled(prefs)) return null

        cached(word)?.let { return it.value }

        return when (val result = BunproClient.searchVocab(prefs.bunproToken, word)) {
            is BunproResult.Ok -> {
                val status = match(result.value, word)
                store(word, status)
                status
            }
            BunproResult.Unauthorized -> {
                prefs.bunproTokenRejected = true
                null
            }
            BunproResult.Failed -> null
        }
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
    private fun match(section: BunproSection, word: String): WordStatus? {
        val item = section.data.firstOrNull { candidate ->
            val a = candidate.attributes
            a.title == word || a.slug == word || a.kana == word
        } ?: return null
        return WordStatus(
            vocabId = item.attributes.id,
            slug = item.attributes.slug,
            jmdictId = item.attributes.jmdictId,
            srs = section.srsFor(item),
        )
    }
}
