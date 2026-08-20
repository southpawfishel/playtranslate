package com.playtranslate.bunpro

import android.content.Context
import com.playtranslate.Prefs
import com.playtranslate.dictionary.JaToken
import com.playtranslate.dictionary.SudachiJapaneseTokenizer

/**
 * "What grammar is in this sentence, and does any of it apply to the word the
 * user tapped?"
 *
 * Ties together the three pieces built separately: the catalogue mirror
 * ([BunproGrammarStore]), the user's studied set (the relevance signal), and
 * the matcher itself ([BunproGrammarIndex]).
 *
 * The index is built once per process and reused — ~979 points with ~2,600
 * aliases is cheap to hold but not cheap to rebuild on every tap. [invalidate]
 * drops it after a sync or a token change.
 */
object BunproGrammarLookup {

    @Volatile private var cached: BunproGrammarIndex? = null
    @Volatile private var studied: Map<Long, Int> = emptyMap()

    /** Drop the in-memory index — after a catalogue sync, or when the token
     *  changes and the mirror is wiped. */
    @Synchronized
    fun invalidate() {
        cached = null
        studied = emptyMap()
    }

    /** True when a lookup could return anything: feature on, token saved,
     *  Japanese source, and a catalogue actually synced. */
    suspend fun isReady(ctx: Context): Boolean =
        BunproLookup.isEnabled(Prefs(ctx.applicationContext)) &&
            BunproGrammarStore.indexSize(ctx) > 0

    private suspend fun index(ctx: Context): BunproGrammarIndex? {
        cached?.let { return it }
        // All disk reads happen BEFORE the lock: they suspend, and a suspension
        // point inside a critical section is both illegal here and a good way
        // to hold a monitor across IO.
        val points = BunproGrammarStore.loadIndex(ctx)
        if (points.isEmpty()) return null
        // Conjugation tables from any detail pages already fetched. Absent
        // until a detail sweep runs — the matcher works without them, since
        // the lemma arm covers much of the same ground.
        val extra = BunproGrammarStore.loadStructureForms(ctx)
        val known = BunproGrammarStore.loadStudied(ctx)
        return synchronized(this) {
            // Another caller may have won the race; its index is equivalent.
            cached ?: BunproGrammarIndex(points, extraForms = extra).also {
                cached = it
                studied = known
            }
        }
    }

    /**
     * Grammar in [sentence] that overlaps the character range
     * [[spanBegin], [spanEnd]) — i.e. the word the user tapped.
     *
     * Scoping to the tapped span is what keeps this useful. Matching a whole
     * sentence yields about one hit per line on real game text, most of it N5
     * conjugation; the user asked about ONE word, so only grammar touching that
     * word is an answer to their question.
     *
     * Returns empty rather than throwing on any failure — this decorates a
     * popup that must render regardless.
     */
    suspend fun forSpan(
        ctx: Context,
        sentence: String,
        spanBegin: Int,
        spanEnd: Int,
    ): List<GrammarMatch> {
        if (sentence.isBlank() || spanEnd <= spanBegin) return emptyList()
        if (!BunproLookup.isEnabled(Prefs(ctx.applicationContext))) return emptyList()
        val idx = index(ctx) ?: return emptyList()
        val tokens = tokenize(sentence)
        return try {
            idx.match(sentence, tokens, srsOf = { studied[it] })
                .filter { it.begin < spanEnd && spanBegin < it.end }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** All grammar in [sentence], for a surface that shows sentence-level
     *  findings rather than answering about one word. */
    suspend fun forSentence(ctx: Context, sentence: String): List<GrammarMatch> {
        if (sentence.isBlank()) return emptyList()
        if (!BunproLookup.isEnabled(Prefs(ctx.applicationContext))) return emptyList()
        val idx = index(ctx) ?: return emptyList()
        return try {
            idx.match(sentence, tokenize(sentence), srsOf = { studied[it] })
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Tokenization powers the lemma arm; without it only literal surface
     *  patterns match. Degrades to empty, matching the Provider contract. */
    private fun tokenize(text: String): List<JaToken> = try {
        SudachiJapaneseTokenizer.Provider.analyze(text)
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Adds a grammar point to the user's Bunpro reviews.
     *
     * Same endpoint as the vocab add, with `GrammarPoint` in the
     * `[type, id]` tuple. On success the point joins the studied set, so it
     * stops being surfaced as something to learn — the matcher's relevance
     * filter picks that up on the next lookup once the index is invalidated.
     */
    suspend fun addToReviews(ctx: Context, pointId: Long): Boolean {
        val prefs = Prefs(ctx.applicationContext)
        if (prefs.bunproToken.isBlank()) return false
        return when (
            val r = BunproClient.addToReviews(
                prefs.bunproToken, pointId, BunproClient.TYPE_GRAMMAR,
            )
        ) {
            is BunproResult.Ok -> {
                // Record locally so the point disappears from future matches
                // without waiting for a full studied re-sync.
                BunproGrammarStore.markStudied(ctx, pointId)
                invalidate()
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
     * Refreshes the studied set from Bunpro — one request, no pagination.
     * Returns true on success. A 401 flags the token as expired, the same as
     * every other read.
     */
    suspend fun syncStudied(ctx: Context): Boolean {
        val prefs = Prefs(ctx.applicationContext)
        if (prefs.bunproToken.isBlank()) return false
        return when (val r = BunproClient.studiedGrammar(prefs.bunproToken)) {
            is BunproResult.Ok -> {
                BunproGrammarStore.saveStudied(ctx, r.value)
                invalidate()
                true
            }
            BunproResult.Unauthorized -> {
                prefs.bunproTokenRejected = true
                false
            }
            BunproResult.Failed -> false
        }
    }
}
