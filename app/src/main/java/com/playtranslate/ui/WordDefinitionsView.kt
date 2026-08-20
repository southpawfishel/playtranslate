package com.playtranslate.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isNotEmpty
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.playtranslate.R
import com.playtranslate.bunpro.BunproHtml
import com.playtranslate.bunpro.BunproLookup
import com.playtranslate.themeColor
import kotlinx.coroutines.launch

/**
 * Renders the dictionary body shared by the magnifying lens
 * ([MagnifierLens]) and the translation-result word cell ([WordResultCell]):
 * a meta row (Common pill · frequency stars · Anki deck pill), an optional
 * warning label, and the numbered senses with per-sense POS headers.
 *
 * The whole body multiplies by a [bind] `scale` factor (text sizes and the
 * structural gaps), so the same renderer serves the small floating lens and
 * the full-width result cell. The design (sizes, the accent Common pill, the
 * uppercase tracked POS, the number-column definitions) follows the
 * dictionary-lookup handoff; the **frequency stars deliberately keep the
 * app's existing 0–5 filled-star system** rather than the handoff's 0–3
 * filled/empty design.
 *
 * Colours are resolved from the view's own (themed) context, so callers pass
 * a context carrying the right theme + accent (the lens via
 * `overlayThemedContext`, the cell via its activity).
 */
class WordDefinitionsView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).toInt()

    /** Overrides how a Bunpro pill tap is handled. Set by surfaces that can't
     *  use the foreground-activity path — see [onBunproPillTapped]. */
    var onBunproPillClick: ((WordDefinitionData) -> Unit)? = null

    /** Notifies the host that this word's Bunpro standing changed under it
     *  (the user added it to reviews from the pill), so the host can keep its
     *  own copy of the data in step. [WordResultCell] routes this to
     *  `updateBunpro`; without it a later Anki-deck refresh would re-bind from
     *  stale data and revert the pill. */
    var onBunproOutcomeChanged: ((BunproLookup.Outcome) -> Unit)? = null

    private val primaryText = context.themeColor(R.attr.ptText)
    private val secondaryText = context.themeColor(R.attr.ptTextMuted)
    private val hintText = context.themeColor(R.attr.ptTextHint)
    private val warnColor = context.themeColor(R.attr.ptWarning)

    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /**
     * Fill for the neutral data chips (the Anki deck pill and the frequency
     * chips): ptSurface by default — one step off the ptCard list surface
     * the result cell sits on. The lens panel is ITSELF ptSurface, so that
     * host overrides this to ptCard (one step lighter) — with the default
     * fill the chips vanish into the panel.
     */
    var metaChipFill: Int = context.themeColor(R.attr.ptSurface)

    /**
     * Opt-in empty-state copy. When non-null AND [bind] receives a [data]
     * with no senses, the body renders this single muted line (the "no
     * dictionary entry" placeholder) instead of nothing. Left null by
     * default, so callers that legitimately bind empty senses (e.g.
     * [WordResultCell]) keep rendering nothing.
     *
     * The view can't see the source entry — [WordDefinitionData] drops it
     * (see `toLensData`) — so "no senses" is the only available "nothing to
     * show" signal. A malformed entry that yields zero renderable senses
     * would therefore also surface this; accepted, since "no senses"
     * honestly reads as "no definitions."
     */
    var emptyPlaceholder: String? = null

    init {
        orientation = VERTICAL
    }

    /**
     * Replace the body with [data]. [label] is an optional warning line
     * (carrying its own leading "⚠ " glyph) shown between the meta row and
     * the senses; pass null for none. [scale] multiplies every size — the
     * lens passes a small factor, the result cell the handoff's "large"
     * default.
     */
    fun bind(data: WordDefinitionData, label: String?, scale: Float, showMisc: Boolean = true) {
        removeAllViews()

        // Keyed on "does Bunpro actually render something" rather than on a
        // specific outcome — Unavailable AND Inconclusive both draw nothing,
        // and an empty meta row would otherwise take up space.
        val hasMetaContent = data.isCommon || data.freqScore > 0 ||
            data.frequencies.isNotEmpty() || data.ankiDecks.isNotEmpty() ||
            BunproBadge.label(context, data.bunpro) != null

        if (hasMetaContent) addView(buildMetaRow(data, scale), fullWidth())

        label?.takeIf { it.isNotBlank() }?.let { warning ->
            val view = TextView(context).apply {
                text = warning
                setTextColor(warnColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * scale)
            }
            addView(view, fullWidth(topMargin = if (isNotEmpty()) dp(8f * scale) else 0))
        }

        // Gap between the meta/warning sections and the first sense.
        val sensesTop = if (isNotEmpty()) dp(9f * scale) else 0
        var previousPos: List<String>? = null
        var firstSenseBlock = true
        data.senses.forEachIndexed { i, sense ->
            // A new POS header is emitted only when the POS actually changes
            // (or is the first non-empty POS seen). Imported rows carry a
            // verbatim dictionary-name header; everything else localizes.
            if (sense.pos.isNotEmpty() && sense.pos != previousPos) {
                val label =
                    if (sense.imported) sense.pos.joinToString(" · ")
                    else context.localizePos(sense.pos)
                val header = TextView(context).apply {
                    text = label.uppercase()
                    // Imported rows with a per-dict accent override tint the
                    // title; everything else uses the muted secondary color.
                    setTextColor(sense.accentColor ?: secondaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f * scale)
                    typeface = medium
                    letterSpacing = 0.07f
                }
                val top = if (firstSenseBlock) sensesTop else dp(10f * scale)
                addView(header, fullWidth(topMargin = top, bottomMargin = dp(4f * scale)))
                previousPos = sense.pos
                firstSenseBlock = false
            }
            val top = if (firstSenseBlock) sensesTop else 0
            addView(
                buildDefinitionRow(i + 1, sense.definition, scale, clamp = sense.imported),
                fullWidth(topMargin = top),
            )
            // Register-tag line under the gloss. renderMisc is the render-side
            // cleanliness authority (localizes known tags, passes domain/region
            // through, drops noise). Suppressed on the drag lens (showMisc=false)
            // so its compact layout is unchanged — the tap-through detail sheet
            // still shows misc (it re-resolves independently).
            if (showMisc) {
                context.renderMiscText(sense.misc)?.let { miscText ->
                    addView(
                        buildMiscRow(miscText, scale),
                        fullWidth(topMargin = dp(2f * scale)).also { it.marginStart = dp(25f * scale) },
                    )
                }
            }
            firstSenseBlock = false
        }

        // Opt-in empty-state: a word with no renderable senses shows the
        // muted placeholder (the "no dictionary entry" case) instead of a
        // blank body. Callers that don't set [emptyPlaceholder] render
        // nothing here, as before.
        if (data.senses.isEmpty()) emptyPlaceholder?.let { placeholder ->
            addView(
                TextView(context).apply {
                    text = placeholder
                    setTextColor(secondaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * scale)
                },
                fullWidth(topMargin = if (isNotEmpty()) dp(8f * scale) else 0),
            )
        }

        addGrammarLines(data, scale)
    }

    /**
     * Bunpro grammar from the surrounding sentence, one line per point.
     *
     * Deliberately terser than the word-detail sheet's block: this renders in
     * the magnifying lens, a popup floating over a game, where vertical space
     * is the scarce resource. Title, level and meaning on one wrapped line is
     * enough to answer "is there grammar here worth studying?" — the full
     * treatment lives in the detail sheet.
     *
     * Empty by default, so surfaces that never populate [WordDefinitionData.grammar]
     * are unchanged.
     */
    private fun addGrammarLines(data: WordDefinitionData, scale: Float) {
        if (data.grammar.isEmpty()) return
        addView(
            TextView(context).apply {
                text = context.getString(R.string.word_detail_group_grammar).uppercase()
                setTextColor(hintText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f * scale)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            },
            fullWidth(topMargin = dp(8f * scale)),
        )
        data.grammar.forEach { m ->
            val c = m.primary
            val level = GrammarResultCell.jlptLabel(c.level)
            // The point itself carries the emphasis (medium, primary) and the
            // gloss stays muted, so a glance separates "which grammar" from
            // "what it means" — the same split the full grammar row makes with
            // its title and meaning lines.
            addView(
                TextView(context).apply {
                    text = android.text.SpannableStringBuilder().apply {
                        val head = buildString {
                            append(c.title)
                            if (!level.isNullOrBlank()) append(" · ").append(level)
                        }
                        append(head)
                        setSpan(
                            android.text.style.StyleSpan(Typeface.BOLD),
                            0, head.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        BunproHtml.toPlainTextOrNull(c.meaning)?.let { append(" — ").append(it) }
                    }
                    setTextColor(secondaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f * scale)
                },
                fullWidth(topMargin = dp(3f * scale)),
            )
        }
    }

    /** Common pill · stars · frequency chips · Anki deck pill, wrapping. */
    private fun buildMetaRow(data: WordDefinitionData, scale: Float): FlowLayout {
        val row = FlowLayout(context).apply { lineSpacingPx = dp(7f * scale) }
        val gap = dp(8f * scale)
        fun add(view: View) {
            val lp = ViewGroup.MarginLayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            if (row.isNotEmpty()) lp.marginStart = gap
            row.addView(view, lp)
        }
        if (data.isCommon) {
            add(
                BadgeChips.commonPill(
                    context,
                    textSizeSp = 11.5f * scale,
                    horizontalPadPx = dp(8f * scale),
                    verticalPadPx = dp(3f * scale),
                )
            )
        }
        if (data.freqScore > 0) {
            // Kept as the app's filled-star system (0–5), not the handoff's
            // 0–3 filled/empty design.
            add(BadgeChips.filledStars(context, data.freqScore, 13f * scale, secondaryText))
        }
        for (tag in data.frequencies) {
            add(
                BadgeChips.freqChip(
                    context,
                    tag,
                    // With an accent override the chip is a filled pill: text
                    // takes the default chip background color so it reads as
                    // knocked out of the accent fill.
                    textColor = if (tag.accentColor != null) metaChipFill else secondaryText,
                    background = freqChipBackground(tag.accentColor),
                    textSizeSp = 11.5f * scale,
                    horizontalPadPx = dp(8f * scale),
                    verticalPadPx = dp(2f * scale),
                )
            )
        }
        if (data.ankiDecks.isNotEmpty()) {
            AnkiDeckBadge.buildPill(
                ctx = context,
                deckNames = data.ankiDecks,
                textColor = secondaryText,
                background = metaChipBackground(),
                textSizeSp = 11.5f * scale,
                horizontalPadPx = dp(8f * scale),
                verticalPadPx = dp(2f * scale),
            )?.let { add(it) }
        }
        // Bunpro status. buildPill returns null for Unavailable, so a surface
        // that never resolved a lookup (or has the feature off) adds nothing.
        BunproBadge.buildPill(
            ctx = context,
            outcome = data.bunpro,
            studiedColor = secondaryText,
            mutedColor = hintText,
            background = metaChipBackground(),
            textSizeSp = 11.5f * scale,
            horizontalPadPx = dp(8f * scale),
            verticalPadPx = dp(2f * scale),
            onClick = { onBunproPillTapped(data) },
        )?.let { add(it) }
        return row
    }

    /**
     * Bunpro pill tap: inspect the near misses, or confirm-then-add an
     * unstudied word.
     *
     * The add half used to exist only on the word-detail sheet, so the same
     * pill was live on one screen and dead on the next. It now works on every
     * surface that can host a dialog and outlive a request — which is the
     * split that matters. The magnifying lens is deliberately NOT one of
     * those: it draws over a game with no PlayTranslate activity foregrounded
     * (so `OverlayAlert.show()` would defer the alert indefinitely) and it is
     * dismissed the moment you lift your finger. A tappable pill there would
     * be a dead control, so the lens keeps a passive badge.
     *
     * [findViewTreeLifecycleOwner] is what draws that line, and it draws it
     * honestly rather than by naming surfaces: a view in an activity's tree
     * has an owner, a view parented straight to the WindowManager does not.
     */
    private fun onBunproPillTapped(data: WordDefinitionData) {
        onBunproPillClick?.let { it(data); return }
        when (val outcome = data.bunpro) {
            is BunproLookup.Outcome.Inconclusive -> {
                val lines = BunproBadge.candidateLines(context, outcome)
                if (lines.isEmpty()) return
                OverlayAlert.Builder(context)
                    .setTitle(context.getString(R.string.word_bunpro_maybe_dialog_title))
                    .setMessage(
                        context.getString(R.string.word_bunpro_maybe_dialog_intro, data.word) +
                            "\n\n" + lines.joinToString("\n")
                    )
                    .addCancelButton(context.getString(R.string.word_bunpro_maybe_dialog_close))
                    .show()
            }
            is BunproLookup.Outcome.Found -> {
                if (outcome.status.srs.studied) return
                val owner = findViewTreeLifecycleOwner() ?: return
                BunproAddAction.confirmAddWord(
                    ctx = context,
                    scope = owner.lifecycleScope,
                    word = data.word,
                    status = outcome.status,
                ) { ok ->
                    if (!ok) return@confirmAddWord
                    // addToReviews rewrote the cache, so this re-resolves to the
                    // studied outcome with no network call.
                    owner.lifecycleScope.launch {
                        val next = BunproLookup.outcomeFor(context, data.word)
                        onBunproOutcomeChanged?.invoke(next)
                    }
                }
            }
            else -> Unit
        }
    }

    /** [metaChipFill] as the lightly-rounded data-chip shape (the
     *  programmatic equivalent of bg_meta_chip). Fresh instance per
     *  chip — drawables can't be shared across views. */
    private fun metaChipBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(metaChipFill)
        cornerRadius = dp(4f).toFloat()
    }

    /** Frequency-chip background: the dictionary's per-dict accent override
     *  ([accentColor], ARGB) when set, else the neutral [metaChipFill]. */
    private fun freqChipBackground(accentColor: Int?): GradientDrawable = GradientDrawable().apply {
        setColor(accentColor ?: metaChipFill)
        cornerRadius = dp(4f).toFloat()
    }

    /** A right-aligned number column + the gloss text. [clamp] caps the
     *  gloss at a few lines — imported (often monolingual, paragraph-length)
     *  definitions get the cap on this compact surface; the word detail
     *  page shows them in full. */
    private fun buildDefinitionRow(
        number: Int,
        definition: String,
        scale: Float,
        clamp: Boolean,
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(3f * scale), 0, dp(3f * scale))
            addView(TextView(context).apply {
                text = "$number."
                setTextColor(hintText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * scale)
                gravity = Gravity.END
                minWidth = dp(16f * scale)
            })
            addView(TextView(context).apply {
                text = definition
                setTextColor(primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f * scale)
                if (clamp) {
                    maxLines = 4
                    ellipsize = TextUtils.TruncateAt.END
                }
            }, LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(9f * scale) })
        }

    /** The small italic register-tag line shown under a sense's gloss (e.g.
     *  "Honorific · Colloquial"). Muted hint color, sized just below the gloss. */
    private fun buildMiscRow(text: String, scale: Float): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(hintText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f * scale)
            setTypeface(null, android.graphics.Typeface.ITALIC)
        }

    private fun fullWidth(topMargin: Int = 0, bottomMargin: Int = 0): LayoutParams =
        LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            this.topMargin = topMargin
            this.bottomMargin = bottomMargin
        }
}
