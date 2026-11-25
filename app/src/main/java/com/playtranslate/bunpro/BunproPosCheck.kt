package com.playtranslate.bunpro

import com.playtranslate.dictionary.JaCategory
import com.playtranslate.dictionary.JaToken

/** A word class a title can name: `Noun + まで`, `う-Verb (Negative)`. */
internal enum class WordClass { NOUN, VERB, VERB_RU, VERB_U, ADJ, ADJ_I, ADJ_NA }

/**
 * What a point's TITLE says about the words around its pattern.
 *
 * Bunpro writes the context into the title when the same form means different
 * things in different company: `Noun + まで` and `Verb + まで` are two points,
 * `る-Verb (Negative)` and `う-Verb (Negative)` are two more, and
 * `Verb［た・ている］+ Noun` is only that when a noun follows. The alias list
 * carries none of it (all four just list まで, or ない), so a matcher that
 * reads aliases alone offers every one of them wherever the form appears.
 *
 * [before] is the class of the word the pattern attaches to, [after] the class
 * that must follow it. Empty sets say nothing and constrain nothing.
 */
internal class StatedContext private constructor(
    val before: Set<WordClass>,
    val after: Set<WordClass>,
) {
    val isEmpty: Boolean get() = before.isEmpty() && after.isEmpty()

    companion object {
        val NONE = StatedContext(emptySet(), emptySet())

        // Specific labels first: う-Verb must not be read as a bare Verb.
        private val LABEL = Regex(
            "([るう])\\s*-\\s*Verbs?" +
                "|[\\[［]([いな])[\\]］]\\s*Adjectives?" +
                "|([いな])\\s*-\\s*Adj(?:ectives?)?" +
                "|(?<![A-Za-z])(Verb)s?(?![a-z])" +
                "|(?<![A-Za-z])(Adj)(?:ectives?)?(?![a-z])" +
                "|(?<![A-Za-z])(Noun)s?(?![a-z])",
        )

        fun parse(title: String): StatedContext {
            val labels = LABEL.findAll(title).map { m ->
                val g = m.groupValues
                val cls = when {
                    g[1] == "る" -> WordClass.VERB_RU
                    g[1] == "う" -> WordClass.VERB_U
                    g[2] == "い" || g[3] == "い" -> WordClass.ADJ_I
                    g[2] == "な" || g[3] == "な" -> WordClass.ADJ_NA
                    g[4].isNotEmpty() -> WordClass.VERB
                    g[5].isNotEmpty() -> WordClass.ADJ
                    else -> WordClass.NOUN
                }
                m.range to cls
            }.toList()
            if (labels.isEmpty()) return NONE

            // Where the pattern itself is written: Japanese outside the
            // labels (the る of る-Verb is part of a label, not the pattern).
            val pattern = title.indices.filter { i ->
                isJapanese(title[i]) && labels.none { i in it.first }
            }
            if (pattern.isEmpty()) {
                // The title is only labels: `る-Verb (Negative)` describes the
                // word the ending attaches to; `い-Adjective + Noun` also what
                // follows.
                return StatedContext(
                    setOf(labels.first().second),
                    labels.drop(1).map { it.second }.toSet(),
                )
            }
            val first = pattern.first()
            val last = pattern.last()
            // A label BETWEEN pieces of the pattern belongs to a second
            // alternative (`Verb[て]・Noun[で] + B`), and which alternative a
            // hit is cannot be told from here. Say nothing rather than guess.
            if (labels.any { it.first.first > first && it.first.last < last }) return NONE
            return StatedContext(
                labels.filter { it.first.last < first }.map { it.second }.toSet(),
                labels.filter { it.first.first > last }.map { it.second }.toSet(),
            )
        }

        private fun isJapanese(c: Char): Boolean =
            c in '぀'..'ゟ' || c in '゠'..'ヿ' || c in '一'..'鿿'
    }
}

/**
 * Part-of-speech checks on a hit: does the sentence's own analysis agree that
 * this span can be that point?
 *
 * Every rule here was measured on 150 hand-labelled sentences before it was
 * written: together they took wrong matches from 123 to 81 and cost one right
 * one. Each was then narrowed against 1,427 unlabelled sentences, which is
 * where its exceptions come from. They cover four ways a form that IS in the
 * text is still not the grammar:
 *
 *  - the title names a word class the neighbours are not ([admits]);
 *  - the form is a word, and the sentence spells it with pieces of other
 *    words: たて in よっ|た|て, みたい in し|て|み|たい ([isSplitWord]);
 *  - the form is a word, and the token is another word inflected: つい in
 *    嘘をつい|た is つく ([isOtherWordInflected]);
 *  - the form is a run of particles at the start of a clause, where there is
 *    nothing for particles to attach to: でも opening a sentence is "but",
 *    not the でも of 何でも ([Place.particlesAtClauseStart]).
 */
internal object BunproPosCheck {

    /** A span located among the sentence's tokens. */
    class Place(private val all: List<JaToken>, val firstIndex: Int, val lastIndex: Int) {
        /** The tokens the span is made of. */
        val tokens: List<JaToken> get() = all.subList(firstIndex, lastIndex + 1)
        val first: JaToken get() = all[firstIndex]
        val size: Int get() = lastIndex - firstIndex + 1

        /** The token the span attaches to: directly before it, and a word. */
        val prev: JaToken?
            get() = all.getOrNull(firstIndex - 1)
                ?.takeIf { it.end == first.begin && !it.isPunctuation }

        val next: JaToken?
            get() = all.getOrNull(lastIndex + 1)
                ?.takeIf { it.begin == all[lastIndex].end && !it.isPunctuation }

        /**
         * True/false for a span made of two or more particles: whether it
         * opens a clause. Null for any other span, which the rule does not
         * concern.
         *
         * Whitespace is skipped when looking back: captured text is often
         * wrapped mid-phrase, and a line break is not a clause boundary.
         */
        val particlesAtClauseStart: Boolean?
            get() {
                if (size < 2) return null
                for (i in firstIndex..lastIndex) {
                    if (all[i].category != JaCategory.PARTICLE) return null
                }
                var i = firstIndex - 1
                while (i >= 0 && all[i].surface.isBlank()) i--
                return i < 0 || all[i].isPunctuation
            }

        companion object {
            /** Null when the span does not sit on token boundaries, which is
             *  also the case when there are no tokens at all. */
            fun of(tokens: List<JaToken>, begin: Int, end: Int): Place? {
                val first = tokens.indexOfFirst { it.begin == begin }
                if (first < 0) return null
                var last = first
                while (last < tokens.size && tokens[last].end < end) last++
                if (last >= tokens.size || tokens[last].end != end) return null
                return Place(tokens, first, last)
            }
        }
    }

    /**
     * True when the sentence around [place] is what [context] says it must
     * be. An empty context is always satisfied.
     *
     * [form] is what matched and [titleForms] the pattern as the title
     * writes it, for a title that is one pattern (empty for one in several
     * parts). When a verb's point lists a form LONGER than its title's
     * (って under `Verb + て`, される under `Verb［れる・られる］`), the extra
     * kana are the end of the verb itself, so the verb must be inside the
     * span: し|て is, and the particle って after a verb is not.
     */
    fun admits(
        context: StatedContext,
        place: Place,
        form: String = "",
        titleForms: Set<String> = emptySet(),
    ): Boolean {
        if (context.before.isNotEmpty() && context.before.none { before(it, place) }) return false
        if (context.after.isNotEmpty() && context.after.none { after(it, place) }) return false
        val takesVerbEnd = context.before.any { it in VERBS } && form !in titleForms &&
            titleForms.any { it.length < form.length && form.endsWith(it) }
        if (takesVerbEnd && place.first.category != JaCategory.VERB) return false
        return true
    }

    private fun before(cls: WordClass, place: Place): Boolean {
        val first = place.first
        val prev = place.prev
        return when (cls) {
            WordClass.NOUN -> prev != null && (
                prev.category == JaCategory.NOUN || prev.category == JaCategory.PRONOUN ||
                    // Suffixes kept out of the content classes: 田中さん|まで.
                    prev.category == JaCategory.OTHER
                )
            // The pattern either starts with the verb (しよう, して) or
            // follows one, possibly through its auxiliaries (食べ|させ|…).
            WordClass.VERB -> first.category == JaCategory.VERB || prev.isVerbal()
            WordClass.VERB_RU, WordClass.VERB_U -> {
                // These are a verb's own endings, so the span must be made of
                // what verbs end in. んだ listed under the past tense (読んだ)
                // otherwise matches ん|だ, the explanatory のだ.
                val ending = first.category == JaCategory.VERB || first.category == JaCategory.AUX ||
                    first.isConjunctiveParticle
                val host = if (first.category == JaCategory.VERB) first else prev?.takeIf { it.isVerbal() }
                ending && host != null && when (conjugationClass(host)) {
                    null -> true // する, くる, an auxiliary: either class's lesson applies
                    else -> conjugationClass(host) == cls
                }
            }
            WordClass.ADJ -> prev != null && (
                prev.category == JaCategory.ADJ_I || prev.category == JaCategory.ADJ_NA || prev.isCopula()
                )
            WordClass.ADJ_I -> prev?.category == JaCategory.ADJ_I
            WordClass.ADJ_NA -> prev != null && (prev.category == JaCategory.ADJ_NA || prev.isCopula())
        }
    }

    private fun after(cls: WordClass, place: Place): Boolean {
        val next = place.next ?: return false
        return when (cls) {
            WordClass.NOUN -> next.category == JaCategory.NOUN || next.category == JaCategory.PRONOUN
            WordClass.VERB, WordClass.VERB_RU, WordClass.VERB_U -> next.category == JaCategory.VERB
            WordClass.ADJ -> next.category == JaCategory.ADJ_I || next.category == JaCategory.ADJ_NA
            WordClass.ADJ_I -> next.category == JaCategory.ADJ_I
            WordClass.ADJ_NA -> next.category == JaCategory.ADJ_NA
        }
    }

    private fun JaToken?.isVerbal(): Boolean =
        this != null && (category == JaCategory.VERB || (category == JaCategory.AUX && !isCopula()))

    private fun JaToken.isCopula(): Boolean =
        category == JaCategory.AUX && dictionaryForm in COPULAS

    /** う-verb or る-verb, from UniDic's conjugation type; null for anything
     *  else that conjugates (する, くる, auxiliaries) or when not reported. */
    private fun conjugationClass(t: JaToken): WordClass? {
        val type = t.conjugationType ?: return null
        return when {
            type.startsWith("五段") -> WordClass.VERB_U
            type.startsWith("上一段") || type.startsWith("下一段") -> WordClass.VERB_RU
            else -> null
        }
    }

    /**
     * True when [formTokens] (the form analysed alone) is one noun or
     * な-adjective and the sentence writes the same characters as several
     * tokens. A word is not made of pieces of other words: 的 listed as てき
     * is not the て|き of あずかってきた.
     *
     * Particles, conjunctions and adverbs are left alone. The analyser splits
     * those differently in and out of a sentence (だから alone is one
     * conjunction; after a noun it is だ|から), and that difference is not
     * evidence about the grammar.
     *
     * Nor is a split that is grammar plus the endings it attaches by. Alone,
     * たばかり reads as the noun 謀り; in し|た|ばかり it is た + ばかり, and
     * ばかり being a form the catalogue knows ([isGrammar]) says the listing
     * is a pattern built on it, not a word. A verb among the pieces is not
     * an ending: み|たい has たい in it and is still not みたい.
     */
    fun isSplitWord(
        formTokens: List<JaToken>,
        place: Place,
        isGrammar: (String) -> Boolean = { false },
    ): Boolean {
        if (place.size < 2 || formTokens.size != 1 || formTokens[0].category !in NAMES) return false
        val grammar = place.tokens.filter { it.surface.length >= 2 && isGrammar(it.surface) }
        val builtOnGrammar = grammar.isNotEmpty() &&
            (place.tokens - grammar.toSet()).all { it.category.isConjugationGlue }
        return !builtOnGrammar
    }

    /**
     * True when the span is one token, an inflected verb or adjective, and
     * the form analysed alone is some OTHER word: つい the adverb found on
     * つい, the stem of つく. Such a hit stands only for a point whose title
     * says it is that class of word's ending (しよう alone reads as 仕様, but
     * under `Verb[よう]` it is する).
     *
     * An imperative is taken at its word. The catalogue lists imperatives by
     * their own form (しろ, たまえ), and alone those read as nouns (白), so
     * the disagreement there is the analyser's, not the sentence's.
     */
    fun isOtherWordInflected(formTokens: List<JaToken>, place: Place): Boolean {
        if (place.size != 1 || formTokens.size != 1) return false
        val t = place.first
        if (t.inflectionForm?.startsWith("命令形") == true) return false
        return t.surface != t.dictionaryForm &&
            (t.category == JaCategory.VERB || t.category == JaCategory.ADJ_I) &&
            formTokens[0].dictionaryForm != t.dictionaryForm
    }

    /**
     * True when a point is used to OPEN a clause: one of the forms its title
     * names analyses as a conjunction or interjection (それでは and じゃあ in
     * `では・それでは・じゃあ`).
     *
     * Three kana or more. Given が, で, から or て with nothing to attach
     * it to, the analyser calls each a conjunction, which says only that it
     * was handed a particle alone.
     */
    fun opensClause(titleForms: Collection<String>, analyse: (String) -> List<JaToken>): Boolean =
        titleForms.any { form ->
            if (form.length < 3) return@any false
            val toks = analyse(form)
            toks.size == 1 && toks[0].end - toks[0].begin == form.length && (
                toks[0].category == JaCategory.CONJUNCTION || toks[0].category == JaCategory.INTERJECTION
                )
        }

    private val COPULAS = setOf("だ", "です")

    private val VERBS = setOf(WordClass.VERB, WordClass.VERB_RU, WordClass.VERB_U)

    /** The classes [isSplitWord] takes for a word. Verbs and い-adjectives
     *  are left out: a form that reads as one of those alone is an inflected
     *  form (たまえ of たまう), and the analyser splitting it in a sentence
     *  (助け|た|まえ) is as likely its mistake as the catalogue's. */
    private val NAMES = setOf(JaCategory.NOUN, JaCategory.PRONOUN, JaCategory.ADJ_NA)
}
