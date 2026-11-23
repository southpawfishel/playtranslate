package com.playtranslate.bunpro

/**
 * The forms that may trigger one grammar point, after the ones that would
 * misfire have been removed.
 *
 * [requiresAfter] holds the conditional ones: `しか` may trigger しか～ない only
 * when `ない` follows it somewhere later in the sentence.
 */
internal data class PointAliases(
    val aliases: List<String>,
    val requiresAfter: Map<String, List<Set<String>>> = emptyMap(),
)

/**
 * Decides which of a point's listed forms are safe to match on.
 *
 * Bunpro's keyword list is written for SEARCH, where a fragment is a helpful
 * way to find a point. For MATCHING the same fragment is a false hit: measured
 * on 1,427 real captured sentences, ない was reported as "Number + しか〜ない",
 * のだ as the Kansai point "のだ ⇒ ねん", だろう as "ようが～まいが", and every
 * する as "する (Have/Wear)". Each rule below removes one such class, and each
 * is derived from the point's TITLE, which states the real shape of the
 * pattern:
 *
 *  1. **Parts of a multi-part pattern** (`しか～ない`, `だに + しない`). A later
 *     part never triggers; the first part triggers only when the rest follows.
 *  2. **The standard side of a dialect rewrite** (`のだ ⇒ ねん`). Only the
 *     right-hand side is the point; the left is what it replaces.
 *  3. **Conjugation-row families** (`える ける せる てる …` under the potential).
 *     These are verb ENDINGS, valid only when they cut into a verb — the
 *     opposite of the token-boundary rule the matcher enforces — so whenever
 *     one does align it is some other word (せる the causative, てる the
 *     contraction of ている).
 *  4. **Structure-table fragments.** A bold run from a conjugation table is
 *     kept only when it extends a form already accepted (かけます ⊃ かけ).
 *     Otherwise it is a piece of a longer pattern: the table for ている bolds
 *     ます and る, and the one for ずにはすまない bolds では.
 */
internal object BunproAliasFilter {

    /**
     * [namedElsewhere] answers "is this form the title of some OTHER point?".
     * It decides one case only: a structure form that is the title minus its
     * copula. はず for はずだ is the same pattern and is kept; こと for ことだ
     * is the こと point's own name, and stays with that point.
     */
    fun admit(
        point: BunproGrammarPoint,
        structureForms: List<String> = emptyList(),
        namedElsewhere: (String) -> Boolean = { false },
    ): PointAliases {
        val listed = point.japaneseAliases()
        val shape = TitleShape.parse(point.title)
        val family = conjugationFamily(listed, shape.alternatives)
        val requires = HashMap<String, List<Set<String>>>()

        val kept = listed.filter { alias ->
            when {
                alias in family -> false
                shape.replaced.isNotEmpty() -> !isReplacedSide(alias, shape)
                shape.groups.size > 1 -> when {
                    // A piece of a later part: never a trigger.
                    alias !in shape.groups.first() &&
                        shape.groups.any { part -> part.any { it.contains(alias) } } -> false
                    // The whole pattern written out (しかない, or まったくない
                    // for 全く～ない): the rest is already inside it.
                    partsFollow(alias, 1, shape.groups.drop(1)) -> true
                    // The first part, or a spelling variant of it (まったく
                    // for 全く～ない): only when the rest follows.
                    else -> {
                        requires[alias] = shape.groups.drop(1)
                        true
                    }
                }
                else -> true
            }
        }

        // Unconditional forms long enough to be evidence on their own.
        val anchors = kept.filter { it.length >= 2 && it !in requires }
        val extended = structureForms.filter { f ->
            f !in kept && f.length >= 2 && (
                anchors.any { it.contains(f) && it.length > f.length && it.removePrefix(f) in COPULAS } &&
                    !namedElsewhere(f) ||
                    anchors.any { f.contains(it) }
                )
        }
        return PointAliases((kept + extended).distinct(), requires)
    }

    /** True when every later part of a multi-part pattern occurs, in order,
     *  in [text] at or after [from]. */
    fun partsFollow(text: String, from: Int, parts: List<Set<String>>): Boolean {
        var at = from
        for (part in parts) {
            at = part.mapNotNull { alt ->
                text.indexOf(alt, at).takeIf { it >= 0 }?.plus(alt.length)
            }.minOrNull() ?: return false
        }
        return true
    }

    private fun isReplacedSide(alias: String, shape: TitleShape): Boolean {
        val result = shape.alternatives
        if (alias in shape.replaced && alias !in result) return true
        // と思う ⇒ 思う: the result is the standard form minus a piece, so in
        // text it cannot be told apart from ordinary usage.
        if (alias in result && shape.replaced.any { it.contains(alias) }) return true
        val touchesReplaced = shape.replaced.any { alias.contains(it) || it.contains(alias) }
        return touchesReplaced && result.none { alias.contains(it) }
    }

    /**
     * Forms that differ only in their first kana, where those kana share a
     * vowel (え け せ て ね … + る), four or more of them: a conjugation row.
     * The shared vowel is what separates a row from a set of unrelated words
     * that merely rhyme (そんなに こんなに あんなに, or いい 良い よい). Members
     * named in the title itself are the real auxiliary (れる, よう) and stay.
     */
    private fun conjugationFamily(listed: List<String>, titleForms: Set<String>): Set<String> =
        listed.filter { it.length >= 2 && vowelOf(it[0]) != null }
            .groupBy { Triple(it.length, it.drop(1), vowelOf(it[0])) }.values
            .filter { it.size >= FAMILY_MIN }
            .flatten()
            .filterNot { it in titleForms }
            .toSet()

    /** こ/そ/ど words come in threes and share a vowel; real rows run to
     *  seven or more. */
    private const val FAMILY_MIN = 4

    private val COPULAS = setOf("だ", "です")

    private val VOWEL_COLUMNS = listOf(
        "あかさたなはまやらわがざだばぱ",
        "いきしちにひみりぎじぢびぴ",
        "うくすつぬふむゆるぐずづぶぷ",
        "えけせてねへめれげぜでべぺ",
        "おこそとのほもよろをごぞどぼぽ",
    )

    private fun vowelOf(c: Char): Int? =
        VOWEL_COLUMNS.indexOfFirst { c in it }.takeIf { it >= 0 }
}

/**
 * A title read as a pattern. `しか～ない` is two [groups]; `れる・られる` is one
 * group with two alternatives; `のだ ⇒ ねん` has [replaced] = {のだ} and one
 * group {ねん}.
 */
internal class TitleShape private constructor(
    /** The ordered parts of the pattern, each a set of alternatives. */
    val groups: List<Set<String>>,
    /** The left side of a `⇒` rewrite — what the point replaces. */
    val replaced: Set<String>,
) {
    val alternatives: Set<String> = groups.flatten().toSet()

    companion object {
        /** `る-Verb`, `い-Adjectives`, `[い] Adjective`, `［な］Adjective`: a
         *  word-class label, whose kana is not part of the pattern. */
        private val CLASS_LABEL = Regex(
            "[\\[［]?[るういな][\\]］]?\\s*-?\\s*(Verbs?|Adjectives?)",
        )

        fun parse(title: String): TitleShape {
            val sides = title.replace(CLASS_LABEL, " ").split('⇒')
            return if (sides.size == 2) {
                // A rewrite's right side is alternatives, never a sequence.
                TitleShape(
                    groups = listOf(groupsOf(sides[1]).flatten().toSet()).filter { it.isNotEmpty() },
                    replaced = groupsOf(sides[0]).flatten().toSet(),
                )
            } else {
                TitleShape(groupsOf(title.replace(CLASS_LABEL, " ")), emptySet())
            }
        }

        private fun groupsOf(s: String): List<Set<String>> {
            val groups = mutableListOf<MutableSet<String>>()
            val run = StringBuilder()
            var depth = 0
            var alternative = false
            fun flush() {
                if (run.isEmpty()) return
                if (alternative && groups.isNotEmpty()) groups.last() += run.toString()
                else groups += mutableSetOf(run.toString())
                run.clear()
                alternative = false
            }
            for (c in s) {
                when {
                    // Parenthesised text is optional (ため(に), ほか(に)は)
                    // or a gloss ((Potential)); neither is a required part,
                    // and neither breaks the word it sits inside.
                    c == '(' || c == '（' -> depth++
                    c == ')' || c == '）' -> depth = (depth - 1).coerceAtLeast(0)
                    depth > 0 -> Unit
                    c == '・' -> { flush(); alternative = true }
                    isJapaneseChar(c) -> run.append(c)
                    else -> flush()
                }
            }
            flush()
            return groups
        }

        private fun isJapaneseChar(c: Char): Boolean =
            c in '぀'..'ゟ' || c in '゠'..'ヿ' || c in '一'..'鿿'
    }
}
