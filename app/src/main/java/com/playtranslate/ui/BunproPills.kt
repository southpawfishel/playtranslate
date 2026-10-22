package com.playtranslate.ui

import android.content.Context
import com.playtranslate.Prefs
import com.playtranslate.bunpro.BunproLookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Fills the Bunpro status pill on any surface that shows a word.
 *
 * The rule this exists to enforce: **wherever a word can be tapped or
 * hovered, its Bunpro standing is shown.** That was not true for a long time
 * and the gaps were invisible, because a surface with no resolver renders
 * [BunproLookup.Outcome.Unavailable], which is the one outcome [BunproBadge]
 * draws nothing for — so a surface that had simply never been wired looked
 * exactly like a word Bunpro had no answer about. Four surfaces were dark
 * that way (the source-text lens on both its hosts, the dictionary lookup
 * page, the word-detail page's related rows, and both lenses' secondary
 * sections); each had reimplemented the Anki deck back-fill beside it and
 * stopped there.
 *
 * So resolution lives here rather than in each surface: one gate, one
 * concurrency bound, one "skip Unavailable" rule. A surface supplies its
 * words and says what to do with each answer.
 *
 * Costs nothing to over-call: [BunproLookup] caches process-wide, negative
 * answers included, so a word already asked about resolves without a request.
 */
object BunproPills {

    /** Max concurrent lookups. Bunpro has no batch endpoint, so a screen of
     *  words costs one HTTPS request per distinct word; this keeps a large
     *  capture from opening a socket per word. */
    private const val PERMITS = 4

    /**
     * Resolves [words] and hands each answer to [apply] on the caller's
     * dispatcher, as it arrives rather than after a barrier — the common
     * words shouldn't wait on the slowest lookup.
     *
     * Silent and additive: returns immediately when the feature is off, and
     * skips [BunproLookup.Outcome.Unavailable] so a surface never replaces a
     * rendered pill with nothing. [isAlive] is checked before every [apply]
     * so a torn-down view is never touched.
     */
    fun fill(
        ctx: Context,
        scope: CoroutineScope,
        words: List<String>,
        isAlive: () -> Boolean = { true },
        apply: (word: String, outcome: BunproLookup.Outcome) -> Unit,
    ) {
        if (!BunproLookup.isEnabled(Prefs(ctx.applicationContext))) return
        val distinct = words.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return
        scope.launch {
            val gate = Semaphore(PERMITS)
            distinct.map { word ->
                async { word to gate.withPermit { BunproLookup.outcomeFor(ctx, word) } }
            }.forEach { deferred ->
                val (word, outcome) = deferred.await()
                if (!isAlive()) return@launch
                if (outcome == BunproLookup.Outcome.Unavailable) return@forEach
                apply(word, outcome)
            }
        }
    }

    /** [fill] for a single word — the lens surfaces, which resolve one tap at
     *  a time. */
    fun fillOne(
        ctx: Context,
        scope: CoroutineScope,
        word: String,
        isAlive: () -> Boolean = { true },
        apply: (outcome: BunproLookup.Outcome) -> Unit,
    ) = fill(ctx, scope, listOf(word), isAlive) { _, outcome -> apply(outcome) }
}
