package com.playtranslate.ui

import android.content.Context
import android.widget.Toast
import com.playtranslate.R
import com.playtranslate.bunpro.BunproGrammarLookup
import com.playtranslate.bunpro.BunproLookup
import com.playtranslate.themeColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Confirm-then-add for the Bunpro review queue — the one write this app makes.
 *
 * It lives here, rather than in each surface, because the two kinds of Bunpro
 * content (vocabulary and grammar points) were drifting apart: vocab was added
 * by tapping its status pill behind a confirmation, grammar by a bare "+"
 * button that fired immediately. Same destructive-ish write, two different
 * gestures and two different levels of care. Every surface now routes through
 * here, so the confirmation, the wording, and the toast are stated once.
 *
 * The confirmation is deliberate and non-negotiable: this modifies the user's
 * study queue on a server, and a badge is a small target to hit by accident.
 */
object BunproAddAction {

    /**
     * Confirm, then add vocabulary [word].
     *
     * [onResult] reports whether the point is now in the queue, and is called
     * for EVERY ending — including a cancelled confirmation, as `false`. A
     * caller that disabled its pill for the duration has to be told when the
     * user backs out, or the control stays dead until the row is rebuilt.
     * Only a genuine attempt is toasted; cancelling says nothing.
     */
    fun confirmAddWord(
        ctx: Context,
        scope: CoroutineScope,
        word: String,
        status: BunproLookup.WordStatus,
        onResult: (Boolean) -> Unit = {},
    ) {
        confirm(
            ctx, ctx.getString(R.string.word_bunpro_add_message, word),
            onConfirm = {
                scope.launch {
                    report(ctx, BunproLookup.addToReviews(ctx, word, status), onResult)
                }
            },
            onCancel = { onResult(false) },
        )
    }

    /** Confirm, then add the grammar point [title] (`pointId`). Same
     *  [onResult] contract as [confirmAddWord]. */
    fun confirmAddGrammar(
        ctx: Context,
        scope: CoroutineScope,
        title: String,
        pointId: Long,
        onResult: (Boolean) -> Unit = {},
    ) {
        confirm(
            ctx, ctx.getString(R.string.word_bunpro_add_grammar_message, title),
            onConfirm = {
                scope.launch {
                    report(ctx, BunproGrammarLookup.addToReviews(ctx, pointId), onResult)
                }
            },
            onCancel = { onResult(false) },
        )
    }

    private fun confirm(
        ctx: Context,
        message: String,
        onConfirm: () -> Unit,
        onCancel: () -> Unit,
    ) {
        OverlayAlert.Builder(ctx)
            .setTitle(ctx.getString(R.string.word_bunpro_add_title))
            .setMessage(message)
            .addButton(
                label = ctx.getString(R.string.word_bunpro_add_confirm),
                color = ctx.themeColor(R.attr.ptAccent),
            ) { onConfirm() }
            // Fires on the cancel button, the scrim, back, and a lifecycle
            // pause — every way out that isn't the confirm button.
            .addCancelButton(ctx.getString(R.string.word_bunpro_add_cancel)) { onCancel() }
            .show()
    }

    /** The toast is fired against the application context so it survives the
     *  surface that started the add going away mid-request. */
    private fun report(ctx: Context, ok: Boolean, onResult: (Boolean) -> Unit) {
        Toast.makeText(
            ctx.applicationContext,
            if (ok) R.string.word_bunpro_add_done else R.string.word_bunpro_add_failed,
            Toast.LENGTH_SHORT,
        ).show()
        onResult(ok)
    }
}
