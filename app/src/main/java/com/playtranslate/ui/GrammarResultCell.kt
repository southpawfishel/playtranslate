package com.playtranslate.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.playtranslate.R
import com.playtranslate.bunpro.BunproHtml
import com.playtranslate.bunpro.GrammarMatch
import com.playtranslate.bunpro.StoredGrammarDetail
import com.playtranslate.themeColor

/**
 * One Bunpro grammar point found in the captured sentence — the grammar
 * sibling of [WordResultCell], and deliberately built to the same shape:
 *
 *  - the meaning at the vocab gloss's own size, so a grammar row and a word
 *    row read as the same KIND of thing;
 *  - a meta row whose Bunpro pill is literally the vocab pill
 *    ([BunproBadge.buildGrammarPill]) — and, like the vocab pill, tapping it
 *    while unstudied is what adds the point to reviews. It replaced a bare
 *    "+" button: one status, one gesture, whichever kind of Bunpro content
 *    you are looking at;
 *  - the explanation INLINE. Grammar is the part of a sentence a reader is
 *    least likely to be able to look up on their own, and burying it behind a
 *    tap made it something you had to already suspect was there. It is clamped
 *    to a few lines with an expander so a long write-up can't push the rest of
 *    the list off-screen.
 *
 * Detail arrives asynchronously (disk, then at most a bounded number of
 * network fetches per capture — see the caller), so the cell binds with
 * whatever is known and is topped up later via [updateDetail].
 */
class GrammarResultCell @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).toInt()

    private val primaryText = context.themeColor(R.attr.ptText)
    private val secondaryText = context.themeColor(R.attr.ptTextMuted)
    private val hintText = context.themeColor(R.attr.ptTextHint)
    private val warnColor = context.themeColor(R.attr.ptWarning)
    private val accentColor = context.themeColor(R.attr.ptAccent)
    private val chipFill = context.themeColor(R.attr.ptSurface)

    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private val titleView: TextView
    private val meaningView: TextView
    private val metaRow: FlowLayout
    private val alsoView: TextView
    private val nuanceView: TextView
    private val cautionView: TextView
    private val writeupView: TextView
    private val expandView: TextView

    private var boundMatch: GrammarMatch? = null
    /** The point's SRS streak as the row currently shows it — starts from the
     *  match and moves to 0 when an add succeeds, so the pill reflects the
     *  queue the user just changed without waiting for a re-sync. */
    private var streak: Int? = null
    private var onAdd: ((onDone: (Boolean) -> Unit) -> Unit)? = null
    private var addInFlight = false
    private var boundScale: Float = 1f
    private var boundDetail: StoredGrammarDetail? = null
    private var expanded = false
    /** Whether the collapsed write-up actually overflows — only knowable after
     *  a layout pass, so [onMeasure] owns it and the expander reads it. Sticky
     *  once true: expanding is what makes it stop overflowing. */
    private var writeupTruncated = false
    /** Set while the caller has a write-up request in flight for this point,
     *  so the expander reads "Loading…" instead of offering a second tap. */
    private var loadingDetail = false
    private var onLoadDetail: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        // 4dp + each child's 12dp end margin — the same 16dp text inset the
        // word cell's body lands on, so the two lists' text starts and ends on
        // the same columns.
        setPadding(dp(16f), dp(14f), dp(4f), dp(14f))

        titleView = TextView(context).apply {
            setTextColor(primaryText)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = -0.02f
        }
        addView(
            titleView,
            LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { marginEnd = dp(12f) },
        )

        meaningView = addBodyText(topMarginDp = 2f)
        metaRow = FlowLayout(context).also {
            addView(
                it,
                LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    topMargin = dp(8f)
                    marginEnd = dp(12f)
                },
            )
        }
        alsoView = addBodyText(topMarginDp = 6f)
        nuanceView = addBodyText(topMarginDp = 10f)
        cautionView = addBodyText(topMarginDp = 6f)
        writeupView = addBodyText(topMarginDp = 10f)
        expandView = addBodyText(topMarginDp = 4f).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { onExpanderTapped() }
        }
    }

    /** A full-width body TextView with the card's 16dp right inset, GONE until
     *  [bind] gives it something to say. */
    private fun addBodyText(topMarginDp: Float): TextView =
        TextView(context).also {
            it.isGone = true
            addView(
                it,
                LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    topMargin = dp(topMarginDp)
                    marginEnd = dp(12f)
                },
            )
        }

    /**
     * Bind [match] at [scale] — the same factor the word cells use, so the two
     * row types scale together.
     *
     * [detail] is whatever the local mirror already holds (null when nothing
     * has been fetched for this point). [onAdd] performs the add — including
     * its confirmation — and reports success; [onLoadDetail] is invoked when
     * the user asks for an explanation that isn't cached.
     */
    fun bind(
        match: GrammarMatch,
        detail: StoredGrammarDetail?,
        scale: Float,
        onAdd: (onDone: (Boolean) -> Unit) -> Unit,
        onLoadDetail: () -> Unit,
    ) {
        boundMatch = match
        boundScale = scale
        this.onLoadDetail = onLoadDetail
        this.onAdd = onAdd
        streak = match.primary.streak
        addInFlight = false
        val p = match.primary

        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP * scale)
        titleView.text = p.title

        // The gloss sits at the vocab definition's size on purpose: this line
        // is the grammar row's answer to "what does it mean", and rendering it
        // smaller was most of why grammar looked like a footnote. Bunpro
        // returns it as HTML, like every other string it serves.
        bindText(meaningView, BunproHtml.toPlainTextOrNull(p.meaning), 16.5f * scale, primaryText)

        bindText(
            alsoView,
            if (match.isAmbiguous) {
                context.getString(
                    R.string.word_grammar_also_fmt,
                    match.candidates.drop(1).joinToString("、") { it.title },
                )
            } else null,
            12.5f * scale,
            hintText,
        )

        expanded = false
        writeupTruncated = false
        loadingDetail = false
        updateDetail(detail)
    }

    /** Fill in (or refresh) the explanation once it resolves. Safe to call
     *  repeatedly; the row simply shows the most it currently knows. */
    fun updateDetail(detail: StoredGrammarDetail?) {
        boundDetail = detail
        val scale = boundScale
        loadingDetail = false

        refreshMetaRow()

        val caution = BunproHtml.toPlainTextOrNull(detail?.caution)
        bindText(nuanceView, BunproHtml.toPlainTextOrNull(detail?.nuance), 14.5f * scale, secondaryText)
        bindText(
            cautionView,
            caution?.let { context.getString(R.string.word_grammar_caution_fmt, it) },
            13.5f * scale,
            warnColor,
        )
        // Already normalized on the way into the store, so this is a no-op for
        // freshly-fetched rows — it exists for cache entries written before
        // BunproHtml existed.
        bindText(
            writeupView,
            BunproHtml.toPlainTextOrNull(detail?.writeup),
            14.5f * scale,
            secondaryText,
        )
        applyWriteupClamp()
        refreshExpander()
    }

    /**
     * The Bunpro pill, the JLPT level, and the part of speech.
     *
     * Rebuilt wholesale rather than patched, because the pill's own state
     * changes: adding the point flips it from "Not studied" to its new SRS
     * stage, and a row that kept saying "Not studied" after a successful add
     * was the most confusing thing the list could do.
     */
    private fun refreshMetaRow() {
        val scale = boundScale
        val p = boundMatch?.primary ?: return
        metaRow.removeAllViews()
        metaRow.lineSpacingPx = dp(7f * scale)
        val pill = BunproBadge.buildGrammarPill(
            ctx = context,
            streak = streak,
            studiedColor = secondaryText,
            mutedColor = hintText,
            background = chipBackground(),
            textSizeSp = 11.5f * scale,
            horizontalPadPx = dp(8f * scale),
            verticalPadPx = dp(2f * scale),
        )
        // Tappable exactly while there is something to do — an unstudied point,
        // and no add already running. Removal from the queue is deliberately
        // not offered here, so a studied pill is passive, same as vocab.
        if (streak == null && !addInFlight) {
            pill.isClickable = true
            pill.isFocusable = true
            pill.contentDescription = context.getString(R.string.word_grammar_add_cd)
            pill.setOnClickListener { requestAdd() }
        }
        addChip(pill)
        jlptLabel(p.level)?.let { addChip(textChip(it, scale)) }
        BunproHtml.toPlainTextOrNull(boundDetail?.partOfSpeech)?.let { addChip(textChip(it, scale)) }
    }

    private fun requestAdd() {
        val add = onAdd ?: return
        if (addInFlight) return
        addInFlight = true
        refreshMetaRow()
        add { ok ->
            addInFlight = false
            // A fresh add enters the queue at the bottom of the SRS, which is
            // streak 0 — the same thing BunproGrammarStore.markStudied records.
            if (ok) streak = 0
            refreshMetaRow()
        }
    }

    /** Show the "loading" state on the expander while the caller fetches. */
    fun setDetailLoading(loading: Boolean) {
        loadingDetail = loading
        refreshExpander()
    }

    private fun onExpanderTapped() {
        if (loadingDetail) return
        if (boundDetail?.writeup == null) {
            setDetailLoading(true)
            onLoadDetail?.invoke()
            return
        }
        expanded = !expanded
        applyWriteupClamp()
        refreshExpander()
    }

    private fun applyWriteupClamp() {
        if (expanded) {
            writeupView.maxLines = Int.MAX_VALUE
            writeupView.ellipsize = null
        } else {
            writeupView.maxLines = COLLAPSED_LINES
            writeupView.ellipsize = android.text.TextUtils.TruncateAt.END
        }
    }

    /**
     * The expander says one of three things, and nothing at all when there is
     * no explanation to be had (no slug to fetch from):
     * "Loading…" while a fetch is in flight, "Read the explanation" when none
     * is cached, and Show more / Show less once there is one.
     */
    private fun refreshExpander() {
        val writeup = boundDetail?.writeup
        val hasSlug = boundMatch?.primary?.slug?.isNotBlank() == true
        val label = when {
            loadingDetail -> R.string.word_grammar_loading_explanation
            writeup == null -> if (hasSlug) R.string.word_grammar_load_explanation else 0
            // A write-up that already fits needs no control.
            !writeupTruncated -> 0
            expanded -> R.string.word_grammar_show_less
            else -> R.string.word_grammar_show_more
        }
        if (label == 0) {
            expandView.isGone = true
            return
        }
        expandView.isGone = false
        expandView.text = context.getString(label)
        expandView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * boundScale)
        expandView.setTextColor(if (loadingDetail) hintText else accentColor)
        expandView.typeface = medium
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // Overflow is only knowable after the text has been laid out, so the
        // "does this write-up need an expander" question can't be answered in
        // updateDetail. Note that lineCount is NOT the test — maxLines clamps
        // it — so this asks the Layout whether it ellipsized the last line.
        // Acted on only when the answer changes, or it would relayout forever.
        if (writeupView.isVisible && !expanded && !loadingDetail && !writeupTruncated) {
            val layout = writeupView.layout ?: return
            if (layout.lineCount > 0 && layout.getEllipsisCount(layout.lineCount - 1) > 0) {
                writeupTruncated = true
                refreshExpander()
                post { requestLayout() }
            }
        }
    }

    private fun bindText(view: TextView, value: String?, sizeSp: Float, color: Int) {
        val text = value?.takeIf { it.isNotBlank() }
        if (text == null) {
            view.isGone = true
            return
        }
        view.isGone = false
        view.text = text
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        view.setTextColor(color)
    }

    private fun addChip(view: View) {
        val lp = ViewGroup.MarginLayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        if (metaRow.childCount > 0) lp.marginStart = dp(8f * boundScale)
        metaRow.addView(view, lp)
    }

    /** A neutral data chip in the same shape and tone as the word cell's
     *  frequency / deck chips. */
    private fun textChip(text: String, scale: Float): TextView = TextView(context).apply {
        this.text = text
        setTextColor(secondaryText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f * scale)
        typeface = medium
        background = chipBackground()
        setPadding(dp(8f * scale), dp(2f * scale), dp(8f * scale), dp(2f * scale))
    }

    private fun chipBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(chipFill)
        cornerRadius = dp(4f).toFloat()
    }

    companion object {
        /** "JLPT5" is how the catalogue stores it; "N5" is how every learner
         *  reads it. */
        fun jlptLabel(level: String?): String? = level?.takeIf { it.isNotBlank() }?.let {
            if (it.startsWith("JLPT")) "N" + it.removePrefix("JLPT") else it
        }

        /** A notch under the word cell's 27sp headword: grammar titles carry
         *  separators (なんか・なんて) and run several characters longer, so the
         *  same size would wrap most of them. */
        private const val TITLE_SP = 23f

        /** Enough to see what a point is about without letting one long
         *  write-up own the screen. */
        private const val COLLAPSED_LINES = 5
    }
}
