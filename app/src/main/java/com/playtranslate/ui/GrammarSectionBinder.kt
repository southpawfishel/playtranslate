package com.playtranslate.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.bunpro.BunproGrammarLookup
import com.playtranslate.bunpro.BunproLookup
import com.playtranslate.bunpro.GrammarMatch
import com.playtranslate.themeColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The results page's Grammar card: Bunpro grammar found in the captured
 * sentence, its own section above Words.
 *
 * SENTENCE-scoped, and that is the point. Scoping to a tapped word covers
 * only ~10% of characters on real game text, so grammar was effectively
 * invisible — you had to land on exactly the right span. Roughly 36% of
 * sentences carry something once already-studied points are filtered out,
 * and this surfaces all of it without a tap.
 *
 * Additive and silent on failure: the whole section stays GONE when Bunpro
 * is off, no catalogue is synced, or nothing matched — so a capture with no
 * grammar shows no empty heading. Host-agnostic like [WordRowsBinder], so the
 * in-app page and the workspace sentence page render the same card.
 */
class GrammarSectionBinder(
    root: View,
    private val ctx: Context,
    private val prefs: Prefs,
    private val host: Host,
) {
    interface Host {
        /** The host view tree is still live — guards every async re-entry. */
        val isAlive: Boolean

        /** Where the lookups and write-up fetches run. */
        val scope: CoroutineScope

        /** Show a confirmation alert in the host's window. */
        fun present(builder: OverlayAlert.Builder)
    }

    private val section: View = root.findViewById(R.id.grammarSection)
    private val card: View = root.findViewById(R.id.cardGrammar)
    private val container: LinearLayout = root.findViewById(R.id.bunproGrammarContainer)
    private val toggle: ImageButton = root.findViewById(R.id.btnToggleGrammar)

    /** The in-flight render, cancelled by the next one so a slow lookup for
     *  an old sentence can't paint over a newer capture. */
    private var renderJob: Job? = null

    /** The sentence the card currently reflects (or is resolving), so a
     *  re-render of the same source — a backend re-translate — doesn't
     *  re-query and flash the section. */
    private var renderedSentence: String? = null

    init {
        // Tint in code: the workspace inflates this layout with a plain
        // inflater that drops app:tint (same rule as the Words eye).
        toggle.imageTintList = ColorStateList.valueOf(ctx.themeColor(R.attr.ptTextMuted))
        toggle.setOnClickListener {
            prefs.hideGrammarSection = !prefs.hideGrammarSection
            applyVisibility()
        }
    }

    /** The eye collapses the grammar CARD, leaving its header (and so the eye
     *  itself) in place — same contract as Words. Whether the section exists
     *  at all is a separate question, owned by [render]. */
    fun applyVisibility() {
        val hidden = prefs.hideGrammarSection
        card.visibility = if (hidden) View.GONE else View.VISIBLE
        toggle.setImageResource(if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
    }

    /** Grammar is sentence-scoped; hide it while the sentence isn't final
     *  (a Translating placeholder) or the page has left the results. */
    fun hide() {
        renderJob?.cancel()
        renderedSentence = null
        container.removeAllViews()
        section.isGone = true
    }

    fun render(sentence: String?) {
        if (sentence != null && sentence == renderedSentence) return
        hide()
        val sentenceText = sentence?.takeIf { it.isNotBlank() } ?: return
        renderedSentence = sentenceText
        if (!BunproLookup.isEnabled(Prefs(ctx.applicationContext))) return
        renderJob = host.scope.launch {
            val matches = BunproGrammarLookup.forSentence(ctx, sentenceText)
            if (!host.isAlive || matches.isEmpty()) return@launch
            container.removeAllViews()
            val cells = matches.mapIndexed { i, m ->
                if (i > 0) container.addView(inflateDivider())
                GrammarResultCell(ctx).also {
                    bindCell(it, m)
                    container.addView(it)
                }
            }
            section.isVisible = true
            applyVisibility()
            loadDetails(matches, cells)
        }
    }

    private fun bindCell(cell: GrammarResultCell, match: GrammarMatch) {
        val p = match.primary
        cell.bind(
            match = match,
            // Whatever is already on disk, filled in by loadDetails.
            detail = null,
            scale = GRAMMAR_CELL_SCALE,
            onAdd = { onDone ->
                // Same confirm-add-toast path the vocab pill uses; the cell
                // flips its own pill to the new SRS stage from onDone.
                BunproAddAction.confirmAddGrammar(
                    ctx = ctx,
                    scope = host.scope,
                    title = p.title,
                    pointId = p.pointId,
                    present = host::present,
                    onResult = onDone,
                )
            },
            onLoadDetail = {
                host.scope.launch {
                    val detail = BunproGrammarLookup.detailFor(
                        ctx, p.pointId, p.slug, allowFetch = true,
                    )
                    if (!host.isAlive) return@launch
                    cell.updateDetail(detail)
                    if (detail?.writeup == null) {
                        cell.setDetailLoading(false)
                        Toast.makeText(
                            ctx.applicationContext,
                            R.string.word_grammar_writeup_unavailable,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
        )
    }

    /**
     * Fills each grammar row's inline explanation.
     *
     * Anything already mirrored on disk is free and lands immediately.
     * Anything else costs one request, so only the first
     * [GRAMMAR_WRITEUP_FETCH_CAP] rows of a capture are allowed to fetch, and
     * they do it in sequence rather than in parallel — this is a bulk read of
     * someone else's site and it should look like a reader, not a crawler. The
     * rest offer a "Read the explanation" tap. Because every fetch is cached,
     * a game whose grammar recurs settles to zero requests within a few
     * captures.
     */
    private suspend fun loadDetails(
        matches: List<GrammarMatch>,
        cells: List<GrammarResultCell>,
    ) {
        var fetches = 0
        matches.forEachIndexed { i, m ->
            val p = m.primary
            // Ask the mirror first, so a cached point neither spends a
            // request nor consumes the budget.
            val cached = BunproGrammarLookup.detailFor(ctx, p.pointId, p.slug, allowFetch = false)
            if (!host.isAlive) return
            cells[i].updateDetail(cached)
            if (cached?.writeup != null) return@forEachIndexed
            if (fetches >= GRAMMAR_WRITEUP_FETCH_CAP || p.slug.isNullOrBlank()) {
                return@forEachIndexed
            }
            fetches++
            cells[i].setDetailLoading(true)
            val fetchedDetail =
                BunproGrammarLookup.detailFor(ctx, p.pointId, p.slug, allowFetch = true)
            if (!host.isAlive) return
            cells[i].updateDetail(fetchedDetail ?: cached)
        }
    }

    /** 1dp ptDivider line inset from the start by pt_row_h_padding, matching
     *  the Words card's row dividers. */
    private fun inflateDivider(): View {
        val dp1 = ctx.resources.displayMetrics.density.toInt().coerceAtLeast(1)
        return View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp1,
            ).apply {
                marginStart = ctx.resources.getDimensionPixelSize(R.dimen.pt_row_h_padding)
            }
            setBackgroundColor(ctx.themeColor(R.attr.ptDivider))
        }
    }

    private companion object {
        /** Matches the Words card's cell scale so the two lists read alike. */
        const val GRAMMAR_CELL_SCALE = 1.0f

        /** How many grammar write-ups ONE capture may fetch. Rows beyond this
         *  render everything the device already knows and offer an explicit
         *  "Read the explanation" tap. */
        const val GRAMMAR_WRITEUP_FETCH_CAP = 3
    }
}
