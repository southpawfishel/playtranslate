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
         *  keyword it merely lists, or when the title states the word class
         *  around the form and the sentence bears it out (`Noun + まで`
         *  after a noun). Resolves ~40% of contested spans on the benchmark
         *  corpus — helpful, but not sufficient on its own. */
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

        /** A dictionary form matched an alias — catches conjugated single
         *  words (よかった → いい) and conjugated patterns (てしまった →
         *  てしまう) without needing a conjugation table. */
        LEMMA,
    }
}

/**
 * Finds Bunpro grammar points in a tokenized sentence.
 *
 * Three arms, because grammar comes in three shapes. Fixed patterns span
 * several morphemes and are matched against the raw text; single-word points
 * appear conjugated and are matched against [JaToken.dictionaryForm]; and
 * patterns that END in a conjugating word (てしまう, なければならない) appear
 * with that last word conjugated, which needs both at once — see [tokenize].
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
    /**
     * Analyses a FORM (not a sentence), so a multi-token pattern can be
     * matched with its last word conjugated: てしまう is て + しまう, and
     * て + しまっ(しまう) in a sentence is the same pattern.
     *
     * Optional. Without it the index still works from literal text and
     * single-word dictionary forms; with it, on 150 hand-labelled sentences,
     * conjugated てしまう / ておく / ねばならない stop being missed. Pass the
     * same analyser that tokenizes the sentences, or the two will disagree
     * about where morphemes end.
     */
    tokenize: ((String) -> List<JaToken>)? = null,
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

    /**
     * A multi-token form whose last token is in dictionary form, ready to be
     * matched against sentence tokens: every token but the last by surface,
     * the last by dictionary form.
     */
    private class Sequence(val alias: String, val head: List<String>, val lastLemma: String) {
        val size: Int get() = head.size + 1
    }

    /** Each admitted form analysed once. Empty when no analyser was given, or
     *  when it produced nothing for that form. */
    private val formTokens: Map<String, List<JaToken>> =
        if (tokenize == null) emptyMap()
        else admitted.values.flatMap { it.aliases }.distinct().associateWith { form ->
            // An analysis that does not tile the form exactly cannot be
            // trusted to say where its morphemes are.
            tokenize(form).takeIf { toks ->
                toks.isNotEmpty() && toks.first().begin == 0 && toks.last().end == form.length &&
                    toks.zipWithNext().all { (a, b) -> a.end == b.begin }
            }.orEmpty()
        }

    private fun sequenceOf(form: String): Sequence? {
        val toks = formTokens[form].orEmpty()
        if (toks.size < 2) return null
        val last = toks.last()
        // Only a form that ENDS in a dictionary form may match conjugated.
        // なら analyses as an inflection of だ; letting its lemma match would
        // turn every だ, な and で in the language into a hit for なら.
        if (last.surface != last.dictionaryForm || !last.conjugates) return null
        // The copula and た are excluded even in dictionary form. Their
        // inflections are grammar points of their own (でしょう, なら, な, で,
        // たら), so matching by lemma relabelled those: もので became ものだ,
        // いたら became the past tense, and のでしょう swallowed でしょう.
        if (last.dictionaryForm.length < 2 || last.dictionaryForm in COPULAS) return null
        return Sequence(form, toks.dropLast(1).map { it.surface }, last.dictionaryForm)
    }

    /**
     * True when [form] is just [base] conjugated: している is する + ている,
     * ています is ている + ます. Such a form adds nothing the base does not
     * already match by lemma — but as a LONGER span it used to win de-overlap
     * and hide the base, which is how every している came to be labelled
     * "する (Have/Wear)" even with する mastered.
     */
    private fun isConjugationOf(form: String, base: String): Boolean {
        if (form == base) return false
        val toks = formTokens[form].orEmpty()
        val seq = sequenceOf(base)
        val used = when {
            seq != null ->
                if (toks.size > seq.size &&
                    seq.head.indices.all { toks[it].surface == seq.head[it] } &&
                    toks[seq.size - 1].dictionaryForm == seq.lastLemma
                ) seq.size else return false
            toks.isNotEmpty() && toks[0].dictionaryForm == base && toks[0].surface != base &&
                toks[0].conjugates -> 1
            else -> return false
        }
        return toks.size > used && toks.drop(used).all {
            it.category.isConjugationGlue || it.isAuxiliaryCapable
        }
    }

    /** alias → the point ids that claim it. Aliases are shared (って belongs to
     *  both ということ and the casual-quotation point), so this is one-to-many. */
    private val byAlias: Map<String, List<Long>> = buildMap<String, MutableList<Long>> {
        points.forEach { p ->
            val forms = admitted.getValue(p.id).aliases
            forms.filterNot { form -> forms.any { base -> isConjugationOf(form, base) } }
                .forEach { alias -> getOrPut(alias) { mutableListOf() }.add(p.id) }
        }
    }

    /** What each title says about the words around its pattern — see
     *  [StatedContext]. Only points that say something are held. */
    private val contexts: Map<Long, StatedContext> = points.mapNotNull { p ->
        StatedContext.parse(p.title).takeIf { !it.isEmpty }?.let { p.id to it }
    }.toMap()

    /** The pattern as each such title writes it, read on first use. */
    private val titleForms = java.util.concurrent.ConcurrentHashMap<Long, Set<String>>()

    /** Points that open a clause (では・それでは・じゃあ), as opposed to
     *  attaching to the word before them. Empty without an analyser. */
    private val clauseOpeners: Set<Long> =
        if (tokenize == null) emptySet()
        else points.filter { p ->
            BunproPosCheck.opensClause(TitleShape.parse(p.title).alternatives) { form ->
                formTokens[form] ?: tokenize(form)
            }
        }.mapTo(HashSet()) { it.id }

    /** For the corpus report. */
    internal fun opensClause(pointId: Long): Boolean = pointId in clauseOpeners

    /** Sequences by the surface of their first token, so a sentence token
     *  looks up only the patterns that could start at it. */
    private val sequencesByFirst: Map<String, List<Sequence>> =
        byAlias.keys.mapNotNull(::sequenceOf).groupBy { it.head.first() }

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

        // Literal hits that landed on another word inflected (つい on the
        // stem of つく). They stand only for a point whose title says the
        // form is that kind of word's ending; see the candidate filter.
        val otherWord = HashSet<Triple<Int, Int, String>>()

        // ── Surface arm ────────────────────────────────────────────────
        for (alias in aliasesByLengthDesc) {
            if (alias.length < minLength) continue
            val alone = formTokens[alias].orEmpty()
            var from = text.indexOf(alias)
            while (from >= 0) {
                val to = from + alias.length
                // With no tokens the check can't run, so it passes — callers
                // without an analysis get the old, looser behaviour rather
                // than silently getting nothing.
                if (aligned(from, to)) {
                    val place = BunproPosCheck.Place.of(tokens, from, to)
                    if (place == null ||
                        !BunproPosCheck.isSplitWord(alone, place, byAlias::containsKey)
                    ) {
                        val span = Triple(from, to, alias)
                        bySpan.putIfAbsent(span, GrammarMatch.Via.SURFACE)
                        if (place != null && BunproPosCheck.isOtherWordInflected(alone, place)) {
                            otherWord += span
                        }
                    }
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
            if (!t.conjugates) continue
            if (t.dictionaryForm.length < minLength) continue
            if (t.dictionaryForm == t.surface) continue // surface arm has it
            if (byAlias.containsKey(t.dictionaryForm)) {
                bySpan.putIfAbsent(
                    Triple(t.begin, t.end, t.dictionaryForm), GrammarMatch.Via.LEMMA,
                )
            }
        }

        // ── Sequence arm ───────────────────────────────────────────────
        // A pattern ending in a conjugating word, found with that word
        // conjugated: て + しまっ(しまう) is てしまう. The uninflected case is
        // left to the surface arm, which reports the same span.
        for (i in tokens.indices) {
            for (seq in sequencesByFirst[tokens[i].surface].orEmpty()) {
                if (seq.alias.length < minLength || i + seq.size > tokens.size) continue
                val run = tokens.subList(i, i + seq.size)
                val last = run.last()
                if (last.dictionaryForm != seq.lastLemma || last.surface == seq.lastLemma) continue
                if (run.zipWithNext().any { (x, y) -> x.end != y.begin }) continue
                if (seq.head.indices.any { run[it].surface != seq.head[it] }) continue
                bySpan.putIfAbsent(
                    Triple(run.first().begin, last.end, seq.alias), GrammarMatch.Via.LEMMA,
                )
            }
        }

        val hits = bySpan.mapNotNull { (span, via) ->
            val (begin, end, form) = span
            // Where the span sits among the tokens; null with no analysis,
            // and then none of the part-of-speech checks can run.
            val place = BunproPosCheck.Place.of(tokens, begin, end)
            val opensClause = place?.particlesAtClauseStart
            val listed = byAlias[form].orEmpty()
            // Does the catalogue split this form by the class of the word
            // before it (`Noun + まで` / `Verb + まで`)? Then the split is the
            // reading, and the half the sentence bears out goes first. One
            // labelled point on its own (`Verb[ないで]` beside ないで) is not
            // a split, and its label being true of the sentence does not
            // make it the better reading.
            val isSplitByClass = listed.mapNotNull { contexts[it] }
                .filter { it.after.isEmpty() }.map { it.before }.distinct().size > 1
            val confirmed = HashSet<Long>()
            val all = listed.mapNotNull { pointsById[it] }
                // A point whose whole title is this form goes ahead of one
                // that names it as an alternative (ように before ように・ような).
                .sortedByDescending { it.titleBase() == form }
                // The first part of a multi-part pattern (しか of しか～ない)
                // counts only when the rest of the pattern follows it.
                .filter { p ->
                    val rest = admitted[p.id]?.requiresAfter?.get(form) ?: return@filter true
                    BunproAliasFilter.partsFollow(text, end, rest)
                }
                .filter { p ->
                    if (place == null) return@filter true
                    val context = contexts[p.id]
                    if (context != null) {
                        val named = titleForms.getOrPut(p.id) {
                            TitleShape.parse(p.title).groups.singleOrNull().orEmpty()
                        }
                        if (!BunproPosCheck.admits(context, place, form, named)) return@filter false
                        // A title that also says what FOLLOWS is about the
                        // phrase the form sits in (`Verb［た・ている］+ Noun`,
                        // a relative clause), not a reading of the form.
                        if (isSplitByClass && context.after.isEmpty()) confirmed += p.id
                    } else if (span in otherWord) {
                        return@filter false
                    }
                    // A run of particles opening a clause is a conjunction
                    // (でも "but"), so only a point used that way fits; the
                    // same run after a word is particles, and such a point
                    // does not (このままでは is not では "well then").
                    opensClause == null || opensClause == (p.id in clauseOpeners)
                }
                // Before a noun, `Noun + まで` is a better reading of まで
                // than the bare point that says nothing of where it goes.
                .sortedByDescending { it.id in confirmed }
            if (all.isEmpty()) return@mapNotNull null
            val candidates = all.map { p ->
                GrammarMatch.GrammarCandidate(
                    pointId = p.id, title = p.title, level = p.level, slug = p.slug,
                    meaning = p.meaning,
                    isPrimaryForm = form in namesOf(p) || p.id in confirmed,
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
            // Literal text is stronger evidence than a dictionary form, so
            // when both arms hit, the points the literal form belongs to go
            // first: にせよ is にせよ・にしろ before it is にする conjugated.
            val literal = group.filter { it.via == GrammarMatch.Via.SURFACE }
            val ordered = if (literal.isEmpty()) group else literal + (group - literal.toSet()).map { m ->
                m.copy(candidates = m.candidates.map { it.copy(isPrimaryForm = false) })
            }
            val pooled = ordered.flatMap { it.candidates }
                .groupBy { it.pointId }.values
                .map { same -> same.first() }
            ordered.first().copy(candidates = pooled)
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

    /**
     * The forms a point is NAMED for: `ている①` → ている, and each side of
     * `にせよ・にしろ`. Only a title that is nothing but its name counts. Any
     * qualifier — "する (Cost)", "Noun + まで", "ている ⇒ てる" — marks a
     * specialised use of a form that some plainer point is named for, and
     * ranking those as equals put the special case first.
     */
    private val names = HashMap<Long, Set<String>>()

    private fun namesOf(p: BunproGrammarPoint): Set<String> = names.getOrPut(p.id) {
        val bare = p.title.trim().trimEnd('①', '②', '③', '④', '⑤').trim()
        if (bare.isEmpty() || !bare.all { it in '぀'..'ゟ' || it in '゠'..'ヿ' || it in '一'..'鿿' }) {
            emptySet()
        } else {
            bare.split('・').filter { it.isNotEmpty() }.toSet()
        }
    }

    /** Title with Bunpro's sense markers stripped: `ている①` → `ている`. */
    private fun BunproGrammarPoint.titleBase(): String =
        title.trim().trimEnd('①', '②', '③', '④', '⑤').trim()

    /** A word that conjugates, so its dictionary form is worth matching:
     *  content words and auxiliaries, never particles. */
    private val JaToken.conjugates: Boolean
        get() = category.isContent || category == JaCategory.AUX

    companion object {
        private val COPULAS = setOf("だ", "です")

        /** Below this, a "pattern" is a bare particle. See the class doc. */
        const val DEFAULT_MIN_LENGTH = 2
    }
}
