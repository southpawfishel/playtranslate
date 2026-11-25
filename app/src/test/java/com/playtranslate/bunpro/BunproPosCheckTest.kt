package com.playtranslate.bunpro

import com.playtranslate.dictionary.JaCategory
import com.playtranslate.dictionary.JaToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The part-of-speech checks: a form that is in the text, and still is not the
 * grammar, because the words around it (or the word it sits on) say so.
 *
 * Token chains are hand-authored, as in [BunproGrammarMatcherTest]: these pin
 * what the matcher does GIVEN an analysis. Each case is one that was measured
 * as a wrong match on real captured sentences before its rule existed.
 */
class BunproPosCheckTest {

    private fun point(id: Long, title: String, vararg aliases: String) =
        BunproGrammarPoint(id = id, title = title, metadata = aliases.joinToString(", "))

    /** Lays [parts] end to end, so offsets need not be counted by hand. */
    private fun tokens(vararg parts: JaToken): List<JaToken> {
        var at = 0
        return parts.map { t ->
            t.copy(begin = at, end = at + t.surface.length).also { at = it.end }
        }
    }

    private fun tok(
        surface: String,
        category: JaCategory,
        lemma: String = surface,
        type: String? = null,
        inflection: String? = null,
        conjunctive: Boolean = false,
        punctuation: Boolean = false,
    ) = JaToken(
        surface = surface, begin = 0, end = surface.length, category = category,
        dictionaryForm = lemma, normalizedForm = lemma, reading = null, isOov = false,
        inflectionForm = inflection, isConjunctiveParticle = conjunctive,
        isPunctuation = punctuation, conjugationType = type,
    )

    private fun noun(s: String) = tok(s, JaCategory.NOUN)
    private fun particle(s: String, conjunctive: Boolean = false) =
        tok(s, JaCategory.PARTICLE, conjunctive = conjunctive)
    private fun godan(surface: String, lemma: String) = tok(surface, JaCategory.VERB, lemma, type = "五段-ラ行")
    private fun ichidan(surface: String, lemma: String) = tok(surface, JaCategory.VERB, lemma, type = "下一段-バ行")
    private fun aux(surface: String, lemma: String = surface) = tok(surface, JaCategory.AUX, lemma)
    private fun stop(s: String = "。") = tok(s, JaCategory.OTHER, punctuation = true)

    private fun analyser(vararg forms: Pair<String, List<JaToken>>): (String) -> List<JaToken> {
        val known = forms.toMap()
        return { known[it].orEmpty() }
    }

    private fun List<JaToken>.text() = joinToString("") { it.surface }

    private fun BunproGrammarIndex.titlesIn(toks: List<JaToken>): List<List<String>> =
        match(toks.text(), toks).map { m -> m.candidates.map { it.title } }

    // ── What a title states ──────────────────────────────────────────────

    @Test fun `a title's labels are read as the word before and the word after`() {
        fun before(t: String) = StatedContext.parse(t).before
        fun after(t: String) = StatedContext.parse(t).after

        assertEquals(setOf(WordClass.NOUN), before("Noun + まで"))
        assertEquals(setOf(WordClass.VERB), before("Verb + まで"))
        assertEquals(setOf(WordClass.VERB_U), before("う-Verb (Negative)"))
        assertEquals(setOf(WordClass.VERB_RU), before("る-Verb (Past)"))
        assertEquals(setOf(WordClass.ADJ_I), before("い-Adjectives くない"))
        assertEquals(setOf(WordClass.VERB), before("Verb［れる・られる］"))
        assertEquals(setOf(WordClass.ADJ), before("Adjective + の(は)"))

        assertEquals(setOf(WordClass.VERB), before("Verb［た・ている］+ Noun"))
        assertEquals(setOf(WordClass.NOUN), after("Verb［た・ている］+ Noun"))
        assertEquals(setOf(WordClass.NOUN), after("後(の) Noun"))
        assertEquals(setOf(WordClass.NOUN), after("がある + Noun"))
    }

    @Test fun `a title without labels, or with one between its parts, states nothing`() {
        assertTrue(StatedContext.parse("まで").isEmpty)
        assertTrue(StatedContext.parse("れる・られる (Potential)").isEmpty)
        assertTrue(StatedContext.parse("なん + counter + か").isEmpty)
        // Two alternatives, each with its own class: which one a hit is
        // cannot be told from the title.
        assertTrue(StatedContext.parse("Verb[て]・Noun[で] + B").isEmpty)
        assertTrue(StatedContext.parse("Adjective + て・Noun + で").isEmpty)
    }

    // ── The word before ──────────────────────────────────────────────────

    private val made = point(372, "まで", "まで")
    private val nounMade = point(58, "Noun + まで", "まで")
    private val verbMade = point(360, "Verb + まで", "まで")

    @Test fun `a form the catalogue splits by word class is read by the word before it`() {
        val idx = BunproGrammarIndex(listOf(made, nounMade, verbMade))

        // 前まで: measured wrong as まで "even", with `Verb + まで` offered too.
        assertEquals(
            listOf(listOf("Noun + まで", "まで")),
            idx.titlesIn(tokens(noun("前"), particle("まで"))),
        )
        assertEquals(
            listOf(listOf("Verb + まで", "まで")),
            idx.titlesIn(tokens(godan("戻す", "戻す"), particle("まで"))),
        )
        // After a particle neither label holds, and the bare point is left.
        assertEquals(
            listOf(listOf("まで")),
            idx.titlesIn(tokens(noun("ここ"), particle("に"), particle("まで"))),
        )
    }

    @Test fun `a lone labelled point is checked but does not go ahead of the point named for the form`() {
        val naide = point(96, "ないで", "ないで")
        val request = point(778, "Verb[ないで]", "ないで", "ないでください")
        val idx = BunproGrammarIndex(listOf(naide, request))
        val toks = tokens(godan("言わ", "言う"), aux("ない"), particle("で", conjunctive = true))
        assertEquals(listOf(listOf("ないで", "Verb[ないで]")), idx.titlesIn(toks))
    }

    private val ruNeg = point(51, "る-Verb (Negative)", "ない", "ません")
    private val uNeg = point(888, "う-Verb (Negative)", "ない", "ません")

    @Test fun `a verb's negative is the lesson for that verb's class`() {
        val idx = BunproGrammarIndex(listOf(ruNeg, uNeg))
        assertEquals(
            listOf(listOf("う-Verb (Negative)")),
            idx.titlesIn(tokens(godan("届か", "届く"), aux("ない"))),
        )
        assertEquals(
            listOf(listOf("る-Verb (Negative)")),
            idx.titlesIn(tokens(ichidan("食べ", "食べる"), aux("ない"))),
        )
        // する is neither class; both lessons stay.
        assertEquals(
            listOf(listOf("る-Verb (Negative)", "う-Verb (Negative)")),
            idx.titlesIn(tokens(tok("し", JaCategory.VERB, "する", type = "サ行変格"), aux("ない"))),
        )
    }

    @Test fun `ない that negates no verb is not a verb's negative`() {
        // わけでもない, 容易でない: measured wrong as る-Verb (Negative).
        val idx = BunproGrammarIndex(listOf(ruNeg, uNeg))
        val nai = tok("ない", JaCategory.ADJ_I)
        assertTrue(idx.titlesIn(tokens(noun("わけ"), particle("で"), particle("も"), nai)).isEmpty())
        assertTrue(idx.titlesIn(tokens(noun("容易"), aux("で", "だ"), nai)).isEmpty())
    }

    @Test fun `a verb ending is not found in a particle and the copula`() {
        // あるんだ: んだ is listed under the past tense for 読んだ, and here
        // is ん + だ, the explanatory のだ.
        val past = point(41, "う-Verb (Past)", "った", "んだ", "した")
        val idx = BunproGrammarIndex(listOf(past))
        val toks = tokens(godan("ある", "ある"), particle("ん"), aux("だ"))
        assertTrue(idx.titlesIn(toks).isEmpty())
    }

    @Test fun `a form longer than its title's needs the verb inside it`() {
        val quote = point(358, "って", "って")
        val teForm = point(416, "Verb + て", "て", "って", "して")
        val idx = BunproGrammarIndex(listOf(quote, teForm))

        // とじこめられてるって: the particle after a verb is not a て-form.
        assertEquals(
            listOf(listOf("って")),
            idx.titlesIn(tokens(ichidan("てる", "てる"), particle("って"))),
        )
        // して is the verb and its て.
        assertEquals(
            listOf(listOf("Verb + て")),
            idx.titlesIn(
                tokens(tok("し", JaCategory.VERB, "する", type = "サ行変格"), particle("て", conjunctive = true)),
            ),
        )
    }

    @Test fun `the title's own form after a verb is not asked for one inside it`() {
        val passive = point(145, "Verb［れる・られる］", "れる", "られる")
        val idx = BunproGrammarIndex(listOf(passive))
        // られる ends with れる; it is still the title's form, not れる plus
        // a piece of the verb.
        assertEquals(
            listOf(listOf("Verb［れる・られる］")),
            idx.titlesIn(tokens(ichidan("見つけ", "見つける"), aux("られる"))),
        )
    }

    // ── The word after ───────────────────────────────────────────────────

    @Test fun `a title that ends in a noun needs a noun after the form`() {
        val teiru = point(27, "ている①", "ている")
        val modifier = point(357, "Verb［た・ている］+ Noun", "ている")
        val idx = BunproGrammarIndex(listOf(teiru, modifier))
        val head = arrayOf(
            godan("持っ", "持つ"), particle("て", conjunctive = true),
            tok("いる", JaCategory.VERB, type = "上一段-ア行"),
        )
        assertEquals(
            listOf(listOf("ている①")),
            idx.titlesIn(tokens(*head, particle("の"))),
        )
        // Before a noun it is offered, behind the point named for the form.
        assertEquals(
            listOf(listOf("ている①", "Verb［た・ている］+ Noun")),
            idx.titlesIn(tokens(*head, noun("力"))),
        )
    }

    // ── A word spelled by other words ────────────────────────────────────

    @Test fun `a noun's reading is not found across two other words`() {
        // あずかってきた: 的 lists its reading てき, which here is て + き(くる).
        val teki = point(443, "的", "的", "てき")
        val idx = BunproGrammarIndex(
            listOf(teki),
            tokenize = analyser("てき" to tokens(noun("てき")), "的" to tokens(noun("的"))),
        )
        val toks = tokens(
            godan("あずかっ", "あずかる"), particle("て", conjunctive = true),
            tok("き", JaCategory.VERB, "くる", type = "カ行変格"), aux("た"),
        )
        assertTrue(idx.titlesIn(toks).isEmpty())
        // As one word of the sentence, it is found.
        assertEquals(1, idx.titlesIn(tokens(noun("てき"), particle("を"), godan("倒す", "倒す"))).size)
    }

    @Test fun `a pattern built on other grammar is not mistaken for a split word`() {
        // たばかり alone reads as the noun 謀り. In a sentence it is た +
        // ばかり, and ばかり is grammar the catalogue knows.
        val bakari = point(165, "たばかり", "ばかり", "たばかり")
        val idx = BunproGrammarIndex(
            listOf(bakari),
            tokenize = analyser("たばかり" to tokens(noun("たばかり")), "ばかり" to tokens(particle("ばかり"))),
        )
        val toks = tokens(tok("し", JaCategory.VERB, "する", type = "サ行変格"), aux("た"), particle("ばかり"))
        assertEquals("たばかり", idx.match(toks.text(), toks).single().matchedForm)
    }

    // ── Another word, inflected ──────────────────────────────────────────

    private val tsui = point(488, "つい", "つい")

    @Test fun `an adverb is not found on a verb's stem`() {
        // 嘘をついた: つい is つく.
        val idx = BunproGrammarIndex(
            listOf(tsui), tokenize = analyser("つい" to tokens(tok("つい", JaCategory.ADVERB))),
        )
        val stem = tok("つい", JaCategory.VERB, "つく", type = "五段-カ行", inflection = "連用形-イ音便")
        assertTrue(idx.titlesIn(tokens(noun("嘘"), particle("を"), stem, aux("た"))).isEmpty())
        // The adverb itself still matches.
        val adverb = tok("つい", JaCategory.ADVERB)
        assertEquals(1, idx.titlesIn(tokens(adverb, godan("笑っ", "笑う"), aux("た"))).size)
    }

    @Test fun `an imperative listed by its own form is taken at its word`() {
        // しろ alone reads as 白. In the sentence it is する, commanded.
        val imperative = point(1032, "命令形", "しろ", "こい")
        val idx = BunproGrammarIndex(
            listOf(imperative), tokenize = analyser("しろ" to tokens(noun("しろ"))),
        )
        val shiro = tok("しろ", JaCategory.VERB, "する", type = "サ行変格", inflection = "命令形")
        assertEquals(1, idx.titlesIn(tokens(noun("集中"), shiro)).size)
    }

    @Test fun `a verb's point keeps a form that reads as another word alone`() {
        // しよう alone reads as 仕様; under Verb[よう] it is する.
        val volitional = point(121, "Verb[よう]", "よう", "しよう")
        val idx = BunproGrammarIndex(
            listOf(volitional), tokenize = analyser("しよう" to tokens(noun("しよう"))),
        )
        val shiyou = tok("しよう", JaCategory.VERB, "する", type = "サ行変格", inflection = "意志推量形")
        assertEquals(1, idx.titlesIn(tokens(noun("宣言"), shiyou)).size)
    }

    // ── Particles with nothing to attach to ──────────────────────────────

    private val demo = point(88, "でも", "でも")
    private val dewa = point(450, "では・それでは・じゃあ", "では", "それでは", "じゃあ")
    private val tewa = point(562, "ては", "ては", "では")

    private val particleForms = analyser(
        "でも" to tokens(particle("で"), particle("も")),
        "では" to tokens(particle("で"), particle("は")),
        "それでは" to tokens(tok("それでは", JaCategory.CONJUNCTION)),
        "じゃあ" to tokens(tok("じゃあ", JaCategory.CONJUNCTION)),
    )

    @Test fun `particles that open a clause are not the particle point`() {
        // でも私は大丈夫: "but", which the catalogue's でも is not.
        val idx = BunproGrammarIndex(listOf(demo), tokenize = particleForms)
        val demoToks = arrayOf(particle("で"), particle("も"))

        assertTrue(idx.titlesIn(tokens(*demoToks, noun("私"))).isEmpty())
        assertTrue(idx.titlesIn(tokens(noun("ありがとう"), stop(), *demoToks, noun("私"))).isEmpty())
        // After a word they are particles, and the point.
        assertEquals(1, idx.titlesIn(tokens(noun("何"), *demoToks)).size)
    }

    @Test fun `a point that opens a clause is not found on particles after a word`() {
        val idx = BunproGrammarIndex(listOf(dewa, tewa), tokenize = particleForms)
        val dewaToks = arrayOf(particle("で"), particle("は"))

        // このままでは: measured wrong as では "well then".
        assertEquals(
            listOf(listOf("ては")),
            idx.titlesIn(tokens(noun("まま"), *dewaToks)),
        )
        assertEquals(
            listOf(listOf("では・それでは・じゃあ")),
            idx.titlesIn(tokens(*dewaToks, noun("行こう"))),
        )
    }

    @Test fun `a short particle read alone as a conjunction does not make its point open clauses`() {
        // Handed から with nothing before it, the analyser says conjunction.
        assertFalse(
            BunproPosCheck.opensClause(listOf("から")) { tokens(tok("から", JaCategory.CONJUNCTION)) },
        )
        assertTrue(
            BunproPosCheck.opensClause(listOf("では", "それでは")) { form ->
                if (form == "それでは") tokens(tok(form, JaCategory.CONJUNCTION)) else emptyList()
            },
        )
    }

    // ── No analysis ──────────────────────────────────────────────────────

    @Test fun `without tokens nothing is checked`() {
        val idx = BunproGrammarIndex(listOf(made, nounMade, verbMade, ruNeg))
        val titles = idx.match("前までない", emptyList()).flatMap { m -> m.candidates.map { it.title } }
        assertEquals(setOf("まで", "Noun + まで", "Verb + まで", "る-Verb (Negative)"), titles.toSet())
    }
}
