package com.playtranslate.bunpro

import com.playtranslate.dictionary.JaCategory
import com.playtranslate.dictionary.JaToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lemma experiment: does matching on [JaToken.dictionaryForm] catch
 * conjugated occurrences that the catalogue index alone misses — making the
 * per-point conjugation tables redundant?
 *
 * Sudachi cannot tokenize in a plain JVM test (no packaged `.dic`), so the
 * token chains here are hand-authored, the same approach
 * `JapaneseInflectionAnalyzer` already uses. That means these tests prove the
 * MATCHER behaves correctly given a lemma — not that Sudachi emits that lemma
 * for real input. The second half needs a device; see the summary in
 * `docs/features/bunpro-integration.md`.
 */
class BunproGrammarMatcherTest {

    // ── Catalogue fixtures (real entries) ────────────────────────────────

    private val ii = BunproGrammarPoint(
        id = 7, title = "いい", level = "JLPT5",
        metadata = "いい, 良い, よい, ii, yoi, adjective",
    )
    private val toiukoto = BunproGrammarPoint(
        id = 185, title = "ということ", level = "JLPT4",
        metadata = "ということ, と言う事, って, ってこと, toiukoto, tte, nominalizer",
    )
    private val wa = BunproGrammarPoint(
        id = 3, title = "は", level = "JLPT5",
        metadata = "は, wa, ha, particle, topic marker",
    )
    private val index = BunproGrammarIndex(listOf(ii, toiukoto, wa))

    private fun token(
        surface: String, begin: Int, lemma: String,
        category: JaCategory = JaCategory.ADJ_I,
    ) = JaToken(
        surface = surface, begin = begin, end = begin + surface.length,
        category = category, dictionaryForm = lemma, normalizedForm = lemma,
        reading = null, isOov = false,
    )

    // ── The actual question ──────────────────────────────────────────────

    @Test
    fun `conjugated form is MISSED by surface matching alone`() {
        // 昨日はよかった。 — the catalogue's aliases for いい are いい/良い/よい.
        // よかった is none of them, so text matching cannot see it.
        val text = "昨日はよかった。"
        val surfaceOnly = index.match(text, tokens = emptyList())
        assertFalse(
            "surface matching must not find いい here — that is the whole problem",
            surfaceOnly.any { m -> m.candidates.any { it.pointId == 7L } },
        )
    }

    @Test
    fun `the lemma arm recovers it, with no conjugation table`() {
        // Given Sudachi lemmatizes よかった → いい (the assumption under test),
        // the lemma arm finds the point and reports the SURFACE span so the UI
        // can highlight よかった rather than a dictionary form absent from the text.
        val text = "昨日はよかった。"
        val tokens = listOf(token("よかった", begin = 3, lemma = "いい"))
        val matches = index.match(text, tokens)
        val hit = matches.single { m -> m.candidates.any { it.pointId == 7L } }
        assertEquals(GrammarMatch.Via.LEMMA, hit.via)
        assertEquals(3, hit.begin)
        assertEquals(7, hit.end)
        assertEquals("よかった", text.substring(hit.begin, hit.end))
    }

    @Test
    fun `a structure form that extends an accepted alias is matched`() {
        // かけます is かけ conjugated — the same pattern, so the table earns
        // its keep here.
        val kake = BunproGrammarPoint(id = 307, title = "かけ", metadata = "かけ, kake")
        val idx = BunproGrammarIndex(listOf(kake), extraForms = mapOf(307L to listOf("かけます")))
        assertEquals("かけます", idx.match("食べかけます").single().matchedForm)
    }

    @Test
    fun `a structure form that is only a fragment is not indexed`() {
        // The table for ている bolds ます and る — pieces of the conjugation,
        // not the pattern. Indexed, they labelled every ます as ている.
        val teiru = BunproGrammarPoint(id = 27, title = "ている①", metadata = "ている, ています")
        val idx = BunproGrammarIndex(
            listOf(teiru), extraForms = mapOf(27L to listOf("います", "ます", "いる", "る")),
        )
        assertTrue(idx.match("行きます").isEmpty())
        assertTrue(idx.match("ここにいる").isEmpty())
        assertEquals(1, idx.match("見ている").size)
    }

    // ── Noise control ────────────────────────────────────────────────────

    @Test
    fun `bare particles do not match`() {
        // は appears in the sentence and IS a catalogued grammar point, but
        // surfacing it would fire on nearly every Japanese sentence ever.
        assertFalse(index.match("昨日はよかった。").any { m -> m.candidates.any { it.pointId == 3L } })
    }

    @Test
    fun `a particle still matches when the caller lowers the gate`() {
        // The suppression is a policy knob, not a hard rule.
        assertTrue(index.match("昨日はよかった。", minLength = 1).any { m -> m.candidates.any { it.pointId == 3L } })
    }

    // ── Overlap ──────────────────────────────────────────────────────────

    @Test
    fun `longest match wins over its own substring`() {
        // ということ and って both match inside ということ; reporting both would
        // double-count one piece of grammar.
        val matches = index.match("それはということです。")
        assertEquals(1, matches.count { m -> m.candidates.any { it.pointId == 185L } })
        assertEquals("ということ", matches.first { m -> m.candidates.any { it.pointId == 185L } }.matchedForm)
    }

    // ── Token alignment kills fragment matches ───────────────────────────

    @Test
    fun `a form crossing a morpheme boundary is rejected`() {
        // Measured on-device false positive: でも matched inside なんでもない.
        // Neither なんでも nor なんでもない is catalogued, so the matcher chopped
        // a set phrase into pieces and named a grammar point that isn't there.
        val demo = BunproGrammarPoint(
            id = 88, title = "でも", level = "JLPT4", metadata = "でも, demo, particle",
        )
        val idx = BunproGrammarIndex(listOf(demo))
        val text = "なんでもない"
        // Sudachi splits this as なんでも + ない — でも spans neither.
        val tokens = listOf(
            token("なんでも", begin = 0, lemma = "なんでも", category = JaCategory.ADVERB),
            token("ない", begin = 4, lemma = "ない", category = JaCategory.AUX),
        )
        assertTrue(
            "without tokens the fragment still matches — that was the bug",
            idx.match(text, tokens = emptyList()).isNotEmpty(),
        )
        assertTrue(
            "with tokens the boundary check rejects it",
            idx.match(text, tokens).isEmpty(),
        )
    }

    @Test
    fun `a form that does align to token boundaries still matches`() {
        val demo = BunproGrammarPoint(
            id = 88, title = "でも", level = "JLPT4", metadata = "でも, demo, particle",
        )
        val text = "でもいい"
        val tokens = listOf(
            token("でも", begin = 0, lemma = "でも", category = JaCategory.PARTICLE),
            token("いい", begin = 2, lemma = "いい"),
        )
        assertEquals(1, BunproGrammarIndex(listOf(demo)).match(text, tokens).size)
    }

    // ── Ambiguity is carried, not guessed away ───────────────────────────

    @Test
    fun `a form claimed by several points keeps every candidate`() {
        // って is listed by ということ AND is its own point. Measured on a
        // 243-line corpus, 37% of spans were contested like this. Collapsing to
        // one would mean presenting an arbitrary pick as fact.
        val tte = BunproGrammarPoint(
            id = 358, title = "って", level = "JLPT5", metadata = "って, tte, quotation",
        )
        val hit = BunproGrammarIndex(listOf(toiukoto, tte))
            .match("そうだって。").single()
        assertTrue(hit.isAmbiguous)
        assertEquals(setOf(185L, 358L), hit.candidates.map { it.pointId }.toSet())
    }

    @Test
    fun `the point whose own title matched is ranked first`() {
        // Weak tiebreak: it resolved ~40% of contested spans on the corpus.
        // って IS 358's title; ということ only lists it as a keyword.
        val tte = BunproGrammarPoint(
            id = 358, title = "って", level = "JLPT5", metadata = "って, tte, quotation",
        )
        val hit = BunproGrammarIndex(listOf(toiukoto, tte)).match("そうだって。").single()
        assertEquals(358L, hit.primary.pointId)
        assertTrue(hit.primary.isPrimaryForm)
    }

    // ── Relevance ────────────────────────────────────────────────────────

    @Test
    fun `a span whose every candidate is MASTERED is dropped`() {
        // The single biggest lever: on the corpus this cut shown matches by
        // 64% while keeping the N3+ material.
        val matches = index.match("それはということです。", srsOf = { if (it == 185L) 12 else null })
        assertTrue(
            "ということ is known, so the span should not surface at all",
            matches.none { m -> m.candidates.any { it.pointId == 185L } },
        )
    }

    @Test
    fun `a partly-mastered span narrows to what is still worth showing`() {
        val tte = BunproGrammarPoint(
            id = 358, title = "って", level = "JLPT5", metadata = "って, tte, quotation",
        )
        val hit = BunproGrammarIndex(listOf(toiukoto, tte))
            .match("そうだって。", srsOf = { if (it == 185L) 12 else null }).single()
        assertFalse("the known candidate is gone", hit.isAmbiguous)
        assertEquals(358L, hit.primary.pointId)
    }

    @Test
    fun `mastering the point a form is named for retires the span`() {
        // Measured: with する mastered, every する in the corpus (290 of them)
        // was shown as "する (Have/Wear)". A point that merely lists the form
        // is not what the form means once its namesake is known.
        val suru = BunproGrammarPoint(id = 22, title = "する", metadata = "する, suru")
        val wear = BunproGrammarPoint(
            id = 1112, title = "する (Have/Wear)", metadata = "している, してる, します, する",
        )
        val idx = BunproGrammarIndex(listOf(suru, wear))
        assertEquals(2, idx.match("勉強する").single().candidates.size)
        assertTrue(idx.match("勉強する", srsOf = { if (it == 22L) 12 else null }).isEmpty())
    }

    @Test
    fun `a second point of the same name survives the first being mastered`() {
        val teiru1 = BunproGrammarPoint(id = 27, title = "ている①", metadata = "ている")
        val teiru2 = BunproGrammarPoint(id = 764, title = "ている②", metadata = "ている")
        val hit = BunproGrammarIndex(listOf(teiru1, teiru2))
            .match("見ている", srsOf = { if (it == 27L) 12 else null }).single()
        assertEquals(764L, hit.primary.pointId)
    }

    @Test
    fun `mastering a long pattern does not unmask the fragments inside it`() {
        // なければならない is mastered, so it is not shown — but it still
        // OCCUPIES its span. The trailing ない is part of that pattern, not a
        // separate occurrence of the ない point.
        val mustDo = BunproGrammarPoint(
            id = 100, title = "なければならない", level = "JLPT4",
            metadata = "なければならない, nakerebanaranai",
        )
        val nai = BunproGrammarPoint(
            id = 101, title = "ない", level = "JLPT5", metadata = "ない, nai",
        )
        val idx = BunproGrammarIndex(listOf(mustDo, nai))
        val text = "行かなければならない"
        assertEquals(
            "unmastered, the long pattern wins the span",
            listOf(100L), idx.match(text).map { it.primary.pointId },
        )
        assertTrue(
            "mastered, the span goes quiet rather than surfacing ない",
            idx.match(text, srsOf = { if (it == 100L) 12 else null }).isEmpty(),
        )
    }

    @Test
    fun `a conjugated auxiliary matches by lemma`() {
        // 食べたかった — たい is an auxiliary (助動詞) in UniDic, and it
        // inflects. The lemma arm used to consider content words only.
        val tai = BunproGrammarPoint(
            id = 50, title = "たい", level = "JLPT5", metadata = "たい, tai",
        )
        val text = "食べたかった"
        val tokens = listOf(
            token("食べ", begin = 0, lemma = "食べる", category = JaCategory.VERB),
            token("たかっ", begin = 2, lemma = "たい", category = JaCategory.AUX),
            token("た", begin = 5, lemma = "た", category = JaCategory.AUX),
        )
        val hit = BunproGrammarIndex(listOf(tai)).match(text, tokens).single()
        assertEquals(GrammarMatch.Via.LEMMA, hit.via)
        assertEquals("たかっ", text.substring(hit.begin, hit.end))
    }

    @Test
    fun `a particle is still not matched by lemma`() {
        val kara = BunproGrammarPoint(
            id = 60, title = "から", level = "JLPT5", metadata = "から, kara",
        )
        // Contrived surface ≠ lemma so only the category gate can reject it.
        val tokens = listOf(
            token("からっ", begin = 0, lemma = "から", category = JaCategory.PARTICLE),
        )
        assertTrue(BunproGrammarIndex(listOf(kara)).match("からっ", tokens).isEmpty())
    }

    @Test
    fun `surface and lemma hits on one span pool their candidates`() {
        // よかった is listed by いい only; its lemma よい is claimed
        // by a second point too. Both arms hit the same span, and neither
        // point may be dropped.
        val yoi = BunproGrammarPoint(
            id = 8, title = "よい", level = "JLPT3", metadata = "よい, yoi",
        )
        val iiWithPast = ii.copy(metadata = ii.metadata + ", よかった")
        val idx = BunproGrammarIndex(listOf(iiWithPast, yoi))
        val text = "昨日はよかった。"
        val tokens = listOf(token("よかった", begin = 3, lemma = "よい"))
        val hit = idx.match(text, tokens).single()
        assertEquals(setOf(7L, 8L), hit.candidates.map { it.pointId }.toSet())
    }

    // ── Sequence arm: patterns that end in a conjugating word ────────────

    /** A stand-in analyser for FORMS, keyed by the form's text. */
    private fun analyser(vararg forms: Pair<String, List<JaToken>>): (String) -> List<JaToken> {
        val known = forms.toMap()
        return { known[it].orEmpty() }
    }

    private val te = token("て", begin = 0, lemma = "て", category = JaCategory.PARTICLE)

    @Test
    fun `a pattern matches with its last word conjugated`() {
        // Measured miss: てしまった was not found, because the catalogue lists
        // てしまう and nothing in the sentence is literally that.
        val shimau = BunproGrammarPoint(id = 140, title = "てしまう", metadata = "てしまう")
        val idx = BunproGrammarIndex(
            listOf(shimau),
            tokenize = analyser(
                "てしまう" to listOf(te, token("しまう", 1, "しまう", JaCategory.VERB)),
            ),
        )
        val text = "食べてしまった"
        val tokens = listOf(
            token("食べ", 0, "食べる", JaCategory.VERB),
            token("て", 2, "て", JaCategory.PARTICLE),
            token("しまっ", 3, "しまう", JaCategory.VERB),
            token("た", 6, "た", JaCategory.AUX),
        )
        val hit = idx.match(text, tokens).single()
        assertEquals(GrammarMatch.Via.LEMMA, hit.via)
        assertEquals("てしまっ", text.substring(hit.begin, hit.end))
        assertEquals("てしまう", hit.matchedForm)
    }

    @Test
    fun `the words before the last must match literally`() {
        val shimau = BunproGrammarPoint(id = 140, title = "てしまう", metadata = "てしまう")
        val idx = BunproGrammarIndex(
            listOf(shimau),
            tokenize = analyser(
                "てしまう" to listOf(te, token("しまう", 1, "しまう", JaCategory.VERB)),
            ),
        )
        // を + しまった: the verb しまう "to put away", not the pattern.
        val tokens = listOf(
            token("本", 0, "本", JaCategory.NOUN),
            token("を", 1, "を", JaCategory.PARTICLE),
            token("しまっ", 2, "しまう", JaCategory.VERB),
            token("た", 5, "た", JaCategory.AUX),
        )
        assertTrue(idx.match("本をしまった", tokens).isEmpty())
    }

    @Test
    fun `a form ending in the copula does not match by lemma`() {
        // ものだ would otherwise claim もので, whose で is also an inflection
        // of だ — and is a different piece of grammar.
        val monoda = BunproGrammarPoint(id = 300, title = "ものだ", metadata = "ものだ")
        val idx = BunproGrammarIndex(
            listOf(monoda),
            tokenize = analyser(
                "ものだ" to listOf(
                    token("もの", 0, "もの", JaCategory.NOUN), token("だ", 2, "だ", JaCategory.AUX),
                ),
            ),
        )
        val tokens = listOf(
            token("もの", 0, "もの", JaCategory.NOUN), token("で", 2, "だ", JaCategory.AUX),
        )
        assertTrue(idx.match("もので", tokens).isEmpty())
    }

    @Test
    fun `a form that is another form conjugated is folded into it`() {
        // している is する + ている. As its own longer form it outranked the
        // する hit, so mastering する never retired it: every している was
        // shown as "する (Have/Wear)".
        val suru = BunproGrammarPoint(id = 22, title = "する", metadata = "する")
        val wear = BunproGrammarPoint(
            id = 1112, title = "する (Have/Wear)", metadata = "している, する",
        )
        val shi = token("し", 0, "する", JaCategory.VERB)
        val iru = token("いる", 2, "いる", JaCategory.VERB).copy(isAuxiliaryCapable = true)
        val idx = BunproGrammarIndex(
            listOf(suru, wear),
            tokenize = analyser(
                "する" to listOf(token("する", 0, "する", JaCategory.VERB)),
                "している" to listOf(shi, token("て", 1, "て", JaCategory.PARTICLE), iru),
            ),
        )
        val text = "勉強している"
        val tokens = listOf(
            token("勉強", 0, "勉強", JaCategory.NOUN),
            token("し", 2, "する", JaCategory.VERB),
            token("て", 3, "て", JaCategory.PARTICLE),
            token("いる", 4, "いる", JaCategory.VERB),
        )
        val hit = idx.match(text, tokens).single()
        assertEquals("し", text.substring(hit.begin, hit.end))
        assertEquals(22L, hit.primary.pointId)
        assertTrue(idx.match(text, tokens, srsOf = { if (it == 22L) 12 else null }).isEmpty())
    }

    @Test
    fun `literal text outranks a dictionary form on the same span`() {
        // にせよ is its own point; it is also にする conjugated. The literal
        // reading goes first.
        val niseyo = BunproGrammarPoint(id = 500, title = "にせよ・にしろ", metadata = "にせよ, にしろ")
        val nisuru = BunproGrammarPoint(id = 84, title = "にする", metadata = "にする")
        val idx = BunproGrammarIndex(
            listOf(nisuru, niseyo),
            tokenize = analyser(
                "にする" to listOf(
                    token("に", 0, "に", JaCategory.PARTICLE), token("する", 1, "する", JaCategory.VERB),
                ),
            ),
        )
        val tokens = listOf(
            token("に", 0, "に", JaCategory.PARTICLE), token("せよ", 1, "する", JaCategory.VERB),
        )
        val hit = idx.match("にせよ", tokens).single()
        assertEquals(listOf(500L, 84L), hit.candidates.map { it.pointId })
    }

    @Test
    fun `lemma hits do not duplicate a surface hit for the same span`() {
        // Uninflected いい: the surface arm sees it, and the lemma arm must not
        // add a second identical match.
        val text = "いい本です。"
        val tokens = listOf(token("いい", begin = 0, lemma = "いい"))
        assertEquals(1, index.match(text, tokens).count { m -> m.candidates.any { it.pointId == 7L } })
    }
}
