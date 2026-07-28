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
import com.playtranslate.bunpro.BunproSrsStatus

/**
 * Shared rendering for the "in my Bunpro reviews" pill, the SRS analog of
 * [AnkiDeckBadge]. Each surface supplies its own colours and background so the
 * pill matches that surface's Common pill; the label, the leading icon and the
 * accessibility text are produced here so the surfaces stay in lockstep.
 */
object BunproBadge {

    /**
     * Visible label for [srs]. Mastered outranks the streak (a mastered item's
     * streak is no longer the interesting fact); a studied item with no streak
     * reported falls back to the bare brand.
     */
    fun label(ctx: Context, srs: BunproSrsStatus): String = when {
        srs.mastered -> ctx.getString(R.string.word_bunpro_mastered)
        srs.streak != null -> ctx.getString(R.string.word_bunpro_streak_fmt, srs.streak)
        else -> ctx.getString(R.string.word_bunpro_studied)
    }

    /**
     * Builds a passive (non-clickable) pill for [srs]. Returns null when the
     * user hasn't studied the word, so callers can use the result directly as
     * an add-or-skip — mirroring [AnkiDeckBadge.buildPill]'s empty-list
     * contract. A word Bunpro *has* but the user hasn't started shows nothing
     * until the add-to-reviews action exists to make it actionable.
     *
     * [background] is consumed as-is (pass a fresh instance per call).
     */
    fun buildPill(
        ctx: Context,
        srs: BunproSrsStatus,
        textColor: Int,
        background: Drawable,
        textSizeSp: Float,
        horizontalPadPx: Int,
        verticalPadPx: Int,
    ): TextView? {
        if (!srs.studied) return null
        val text = label(ctx, srs)
        val density = ctx.resources.displayMetrics.density
        val iconPx = (textSizeSp * density).toInt().coerceAtLeast(1)
        val icon: Drawable? = AppCompatResources.getDrawable(ctx, R.drawable.ic_offline_star_filled)
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
            contentDescription = ctx.getString(R.string.word_bunpro_badge_cd, text)
        }
    }
}
