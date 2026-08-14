package com.playtranslate.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import com.playtranslate.R
import com.playtranslate.bunpro.BunproLevel
import com.playtranslate.bunpro.BunproLookup
import com.playtranslate.bunpro.BunproStage

/**
 * Shared rendering for the Bunpro status pill, the SRS analog of
 * [AnkiDeckBadge]. Each surface supplies its own colours and background so the
 * pill matches that surface's Common pill; the label, the leading icon and the
 * accessibility text are produced here so the surfaces stay in lockstep.
 *
 * Three visible states, so "no pill" stops being ambiguous:
 *  - **studied** — accent-toned, filled star, shows the streak
 *  - **in Bunpro, not studied** — muted, outline star
 *  - **not in Bunpro** — muted, outline star
 *
 * A fourth case, [BunproLookup.Outcome.Unavailable], renders NOTHING. We only
 * assert something about a word when a search actually answered.
 */
object BunproBadge {

    /**
     * Visible label for [outcome], or null when there is nothing honest to say.
     * Mastered outranks the streak (a mastered item's streak is no longer the
     * interesting fact); a studied item with no streak reported falls back to
     * the bare brand.
     */
    fun label(ctx: Context, outcome: BunproLookup.Outcome): String? = when (outcome) {
        is BunproLookup.Outcome.Found -> {
            val srs = outcome.status.srs
            val level = srs.level
            when {
                !srs.studied -> ctx.getString(R.string.word_bunpro_not_studied)
                level != null -> ctx.getString(R.string.word_bunpro_level_fmt, stageLabel(ctx, level))
                else -> ctx.getString(R.string.word_bunpro_studied)
            }
        }
        BunproLookup.Outcome.Absent -> ctx.getString(R.string.word_bunpro_absent)
        // Results came back but none matched exactly. We don't claim presence
        // OR absence — we say "maybe" and let the user judge the near misses.
        is BunproLookup.Outcome.Inconclusive -> ctx.getString(R.string.word_bunpro_maybe)
        BunproLookup.Outcome.Unavailable -> null
    }

    /**
     * The near-miss candidates as display lines, e.g.
     * `と言うことは（ということは） — that is to say`, with the user's SRS
     * standing appended when they've studied it. Empty for any other outcome.
     */
    fun candidateLines(ctx: Context, outcome: BunproLookup.Outcome): List<String> {
        val candidates = (outcome as? BunproLookup.Outcome.Inconclusive)?.candidates.orEmpty()
        return candidates.map { c ->
            val head = c.title ?: c.kana ?: c.slug.orEmpty()
            val reading = c.kana?.takeIf { it != head }?.let { "（$it）" } ?: ""
            val gloss = c.meaning?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
            val srs = if (c.srs.studied) "  [${label(ctx, BunproLookup.Outcome.Found(c))}]" else ""
            "$head$reading$gloss$srs"
        }
    }

    /** "Adept 2", or bare "Master" (which has no steps). */
    fun stageLabel(ctx: Context, level: BunproLevel): String {
        val name = ctx.getString(
            when (level.stage) {
                BunproStage.BEGINNER -> R.string.word_bunpro_stage_beginner
                BunproStage.ADEPT -> R.string.word_bunpro_stage_adept
                BunproStage.SEASONED -> R.string.word_bunpro_stage_seasoned
                BunproStage.EXPERT -> R.string.word_bunpro_stage_expert
                BunproStage.MASTER -> R.string.word_bunpro_stage_master
            }
        )
        return level.step?.let { ctx.getString(R.string.word_bunpro_stage_step_fmt, name, it) }
            ?: name
    }

    /** True only for a word the user has actually started studying — the one
     *  state that earns the surface's accent colour and a filled star. */
    private fun isStudied(outcome: BunproLookup.Outcome): Boolean =
        outcome is BunproLookup.Outcome.Found && outcome.status.srs.studied

    /**
     * Whether tapping the pill does anything: inspect the near misses, or add
     * an unstudied word to reviews. A word already in the queue has no action
     * (removal is deliberately not offered), and Absent has nothing to act on.
     */
    fun isActionable(outcome: BunproLookup.Outcome): Boolean = when (outcome) {
        is BunproLookup.Outcome.Inconclusive -> outcome.candidates.isNotEmpty()
        is BunproLookup.Outcome.Found -> !outcome.status.srs.studied
        else -> false
    }

    /**
     * Builds a passive (non-clickable) pill for [outcome]. Returns null ONLY
     * for [BunproLookup.Outcome.Unavailable], so callers can use the result
     * directly as an add-or-skip — mirroring [AnkiDeckBadge.buildPill]'s
     * empty-list contract.
     *
     * [studiedColor] is the surface's accent (used for a word in the user's
     * reviews); [mutedColor] is its recessed tone, carrying both "in Bunpro
     * but not started" and "not in Bunpro" so neither competes with the
     * Common pill for attention.
     *
     * [background] is consumed as-is (pass a fresh instance per call).
     */
    fun buildPill(
        ctx: Context,
        outcome: BunproLookup.Outcome,
        studiedColor: Int,
        mutedColor: Int,
        background: Drawable,
        textSizeSp: Float,
        horizontalPadPx: Int,
        verticalPadPx: Int,
        /** Supplied only for [BunproLookup.Outcome.Inconclusive] — makes the
         *  pill tappable so the near misses can be inspected. Every other
         *  state stays passive, like [AnkiDeckBadge]'s pill. */
        onClick: (() -> Unit)? = null,
    ): TextView? {
        val text = label(ctx, outcome) ?: return null
        val studied = isStudied(outcome)
        val textColor = if (studied) studiedColor else mutedColor
        val iconRes =
            if (studied) R.drawable.ic_offline_star_filled
            else R.drawable.ic_offline_star_outline
        val density = ctx.resources.displayMetrics.density
        val iconPx = (textSizeSp * density).toInt().coerceAtLeast(1)
        val icon: Drawable? = AppCompatResources.getDrawable(ctx, iconRes)
            ?.mutate()
            ?.also {
                DrawableCompat.setTint(it, textColor)
                it.setBounds(0, 0, iconPx, iconPx)
            }
        return TextView(ctx).apply {
            this.text = text
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            this.background = background
            gravity = Gravity.CENTER_VERTICAL
            setCompoundDrawablesRelative(icon, null, null, null)
            compoundDrawablePadding = (4 * density).toInt()
            setPadding(horizontalPadPx, verticalPadPx, horizontalPadPx, verticalPadPx)
            contentDescription =
                if (studied) ctx.getString(R.string.word_bunpro_badge_cd, text)
                else ctx.getString(R.string.word_bunpro_status_cd, text)
            if (onClick != null && isActionable(outcome)) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }
    }
}
