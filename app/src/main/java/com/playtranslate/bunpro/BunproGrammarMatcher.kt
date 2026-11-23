package com.playtranslate.bunpro

import com.playtranslate.dictionary.JaCategory
import com.playtranslate.dictionary.JaToken

/**
 * One grammar span found in a sentence, with every point that could explain it.
 *
 * [candidates] is a list rather than a single point because a matched form
 * frequently maps to several: measured against a 243-line game-script corpus,
 * **37% of surviving spans had more than one candidate** (って → 6 points,
 * ない → 9). Some of that is metadata bleed, but some is irreducible — って as
 * casual quotation vs. casual は is a contextual distinction a lexical matcher
 * cannot make. Collapsing to one point would mean picking arbitrarily and
 * presenting the guess as fact, so the ambiguity is carried to the UI instead.
 */
data class GrammarMatch(
    /** Offsets into the ORIGINAL sentence, so the UI can highlight the span. */
    val begin: Int,
    val end: Int,
    val matchedForm: String,
    val via: Via,
    /** Best-first; [primary] is the head. Never empty. */
    val candidates: List<GrammarCandidate>,
) {
    val length: Int get() = end - begin
    val primary: GrammarCandidate get() = candidates.first()
    val isAmbiguous: Boolean get() = candidates.size > 1

    /** One point that could explain a matched span. */
    data class GrammarCandidate(
        val pointId: Long,
        val title: String,
        val level: String?,
        /** URL slug, for fetching this point's write-up on demand. */
        val slug: String?,
        /** The catalogue's one-line gloss ("as one would expect, only
         *  natural"). Carried so the UI can say what a point MEANS rather than
         *  just naming it. */
        val meaning: String?,
        /** True when the matched form IS this point's own title rather than a
         *  keyword it merely lists. Resolves ~40% of contested spans on the
         *  benchmark corpus — helpful, but not sufficient on its own. */
        val isPrimaryForm: Boolean,
        /** The user's SRS streak for this point, or null when it isn't in
         *  their reviews at all. Drives whether the UI offers "add" or shows
         *  the current stage. */
        val streak: Int?,
    )

    enum class Via {
        /** A literal run of the sentence matched an alias — catches the
         *  multi-token patterns (ということ, なければならない). */
        SURFACE,

        /** A token's dictionary form matched an alias — catches conjugated
         *  single words (よかった → いい) without needing a conjugation table. */
        LEMMA,
    }
}

/**
 * Finds Bunpro grammar points in a tokenized sentence.
 *
 * Two arms, because grammar comes in two shapes. Lexical patterns span several
 * morphemes and are matched against the raw text; single-word points appear
 * conjugated and are matched against [JaToken.dictionaryForm]. Neither arm
 * alone is sufficient.
 *
 * [extraForms] folds in surface forms sourced from somewhere other than the
 * catalogue index — specifically [BunproGrammarDetail.structureForms], the
 * conjugation tables on each point's detail page. Passing them is optional
 * precisely so the value of fetching detail can be MEASURED: run the same
 * corpus with and without, and compare recall.
 *
 * Matching is deliberately conservative about short forms. Roughly a third of
 * the JLPT5 catalogue is single particles (は, が, を, に, の, か …); matching
 * those would fire on nearly every token of every sentence, so [minLength]
 * gates them out by default.
 */
class BunproGrammarIndex(
    points: List<BunproGrammarPoint>,
    extraForms: Map<Long, List<String>> = emptyMap(),
) {

    private val pointsById: Map<Long, BunproGrammarPoint> = points.associateBy { it.id }

    /** What each point may be matched on, once the forms that would misfire
     *  are removed — see [BunproAliasFilter] for the rules and why. */
    private val admitted: Map<Long, PointAliases> = run {
        val namedBy = HashMap<String, MutableSet<Long>>()
        points.forEach { p ->
            TitleShape.parse(p.title).alternatives.forEach { namedBy.getOrPut(it) { HashSet() } += p.id }
        }
        points.associate { p ->
            p.id to BunproAliasFilter.admit(p, extraForms[p.id].orEmpty()) { form ->
                namedBy[form]?.any { it != p.id } == true
            }
        }
    }

    /** The admitted forms for one point — for the corpus report. */
    internal fun admittedFor(pointId: Long): PointAliases =
        admitted[pointId] ?: PointAliases(emptyList())

    /** alias → the point ids that claim it. Aliases are shared (って belongs to
     *  both ということ and the casual-quotation point), so this is one-to-many. */
    private val byAlias: Map<String, List<Long>> = buildMap<String, MutableList<Long>> {
        points.forEach { p ->
            admitted.getValue(p.id).aliases.forEach { alias ->
                getOrPut(alias) { mutableListOf() }.add(p.id)
            }
        }
    }

    /** Every distinct matchable form, longest first so longest-match-wins falls
     *  out of iteration order. */
    private val aliasesByLengthDesc: List<String> =
        byAlias.keys.sortedByDescending { it.length }

    /**
     * Grammar spans present in [text], de-overlapped, each carrying every point
     * that could explain it.
     *
     * [tokens] must be the analysis OF [text] — [JaToken.begin]/[end] index into
     * it, which is what lets a lemma hit report a highlightable span.
     *
     * [srsOf] drives relevance, the single biggest lever here: on the benchmark
     * corpus, suppressing points the user already knows cut shown matches by
     * ~64% while retaining the N3+ material. The threshold is MASTER rather
     * than "studied at all", so a point you learned once and half-forgot still
     * surfaces — that is precisely when you want to re-read it. It does NOT
     * reduce ambiguity; that is a separate problem.
     */
    fun match(
        text: String,
        tokens: List<JaToken> = emptyList(),
        minLength: Int = DEFAULT_MIN_LENGTH,
        /** The user's streak for a point, or null if not in their reviews.
         *  Points at or above [BunproLevel.MASTER_STREAK] are suppressed —
         *  everything below still surfaces, because a half-learned point is
         *  exactly the one worth re-reading. */
        srsOf: (Long) -> Int? = { null },
    ): List<GrammarMatch> {
        // span -> the alias that produced it, and how
        val bySpan = LinkedHashMap<Triple<Int, Int, String>, GrammarMatch.Via>()

        // Morpheme boundaries, for rejecting fragments. Measured failure: でも
        // matched inside なんでもない — a span crossing a word boundary, naming
        // a grammar point that isn't there. Neither なんでも nor なんでもない is
        // catalogued, so the matcher chopped a set phrase into pieces.
        val starts = tokens.mapTo(HashSet()) { it.begin }
        val ends = tokens.mapTo(HashSet()) { it.end }
        val aligned = { b: Int, e: Int -> tokens.isEmpty() || (b in starts && e in ends) }

        // ── Surface arm ────────────────────────────────────────────────
        for (alias in aliasesByLengthDesc) {
            if (alias.length < minLength) continue
            var from = text.indexOf(alias)
            while (from >= 0) {
                val to = from + alias.length
                // With no tokens the check can't run, so it passes — callers
                // without an analysis get the old, looser behaviour rather
                // than silently getting nothing.
                if (aligned(from, to)) {
                    bySpan.putIfAbsent(Triple(from, to, alias), GrammarMatch.Via.SURFACE)
                }
                from = text.indexOf(alias, from + 1)
            }
        }

        // ── Lemma arm ──────────────────────────────────────────────────
        // Content words and auxiliaries. Auxiliaries inflect just like content
        // words do (たかった → たい, なかった → ない, ません → ます), so leaving
        // them out missed every conjugated occurrence of those points.
        // Particles stay out: a particle's dictionary form is the particle,
        // which the surface arm already gates on length for a reason.
        for (t in tokens) {
            if (!t.category.isContent && t.category != JaCategory.AUX) continue
            if (t.dictionaryForm.length < minLength) continue
            if (t.dictionaryForm == t.surface) continue // surface arm has it
            if (byAlias.containsKey(t.dictionaryForm)) {
                bySpan.putIfAbsent(
                    Triple(t.begin, t.end, t.dictionaryForm), GrammarMatch.Via.LEMMA,
                )
            }
        }

        val hits = bySpan.mapNotNull { (span, via) ->
            val (begin, end, form) = span
            val all = byAlias[form].orEmpty().mapNotNull { pointsById[it] }
                // The first part of a multi-part pattern (しか of しか～ない)
                // counts only when the rest of the pattern follows it.
                .filter { p ->
                    val rest = admitted[p.id]?.requiresAfter?.get(form) ?: return@filter true
                    BunproAliasFilter.partsFollow(text, end, rest)
                }
            if (all.isEmpty()) return@mapNotNull null
            val candidates = all.map { p ->
                GrammarMatch.GrammarCandidate(
                    pointId = p.id, title = p.title, level = p.level, slug = p.slug,
                    meaning = p.meaning,
                    isPrimaryForm = form == p.title || form == p.titleBase(),
                    streak = srsOf(p.id),
                )
            }
            GrammarMatch(begin, end, form, via, candidates)
        }

        // The STRUCTURE of the sentence is settled on every hit, mastered or
        // not, and relevance is applied only afterwards. Filtering first let a
        // mastered long pattern vanish before de-overlap, which unmasked the
        // fragments inside it: master なければならない and its trailing ない
        // surfaced as a separate grammar point that isn't there.
        return deOverlap(mergeSameSpan(hits)).mapNotNull { m ->
            // Relevance: drop points the user has MASTERED. Anything below
            // Master survives — studied-but-forgotten is a case worth showing,
            // and it is why this is a streak threshold rather than a boolean.
            val unmastered = m.candidates.filterNot {
                (it.streak ?: -1) >= BunproLevel.MASTER_STREAK
            }
            if (unmastered.isEmpty()) return@mapNotNull null
            // When the point this form is NAMED for is mastered, the span is
            // known. What remains are points that merely list the form —
            // specialised senses such as "する (Cost)" — and showing one of
            // those on every する would be wrong almost every time. A second
            // point of the same name (ている①/②) is still shown.
            val namedMastered = m.candidates.any {
                it.isPrimaryForm && (it.streak ?: -1) >= BunproLevel.MASTER_STREAK
            }
            if (namedMastered && unmastered.none { it.isPrimaryForm }) return@mapNotNull null
            // Primary forms first; the rest keep catalogue order so the
            // ordering is at least stable rather than arbitrary.
            m.copy(candidates = unmastered.sortedByDescending { it.isPrimaryForm })
        }
    }

    /**
     * Folds hits on the SAME span into one, pooling their candidates.
     *
     * The two arms can both claim a span under different forms — よかった as a
     * structure-table surface form and よい as its lemma — and those forms do
     * not always map to the same points. Left separate, de-overlap kept
     * whichever came first and silently discarded the other's candidates.
     */
    private fun mergeSameSpan(hits: List<GrammarMatch>): List<GrammarMatch> =
        hits.groupBy { it.begin to it.end }.values.map { group ->
            if (group.size == 1) return@map group.first()
            val pooled = group.flatMap { it.candidates }
                .groupBy { it.pointId }.values
                .map { same -> same.firstOrNull { it.isPrimaryForm } ?: same.first() }
            group.first().copy(candidates = pooled)
        }

    /**
     * Longest-match-wins. ということは and ということ both match the same run;
     * reporting both would double-count the same piece of grammar, so the
     * longer span claims the region and shorter overlaps are dropped.
     */
    private fun deOverlap(hits: List<GrammarMatch>): List<GrammarMatch> {
        val taken = mutableListOf<GrammarMatch>()
        hits.sortedWith(compareByDescending<GrammarMatch> { it.length }.thenBy { it.begin })
            .forEach { h ->
                val clashes = taken.any { h.begin < it.end && it.begin < h.end }
                if (!clashes) taken += h
            }
        return taken.sortedBy { it.begin }
    }

    /** Title with Bunpro's sense markers stripped: `ている①` → `ている`. */
    private fun BunproGrammarPoint.titleBase(): String =
        title.trimEnd(' ', '①', '②', '③', '④', '⑤')

    companion object {
        /** Below this, a "pattern" is a bare particle. See the class doc. */
        const val DEFAULT_MIN_LENGTH = 2
    }
}
