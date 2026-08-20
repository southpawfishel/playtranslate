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
    fun `structure forms would ALSO catch it — the redundancy being measured`() {
        // Same sentence, no tokens, but with よかった folded in from the detail
        // page's conjugation table. It matches on surface. So for this class of
        // point the two routes overlap, and detail earns its 979 requests only
        // where the lemma arm falls short.
        val withStructure = BunproGrammarIndex(
            listOf(ii, toiukoto, wa),
            extraForms = mapOf(7L to listOf("よくない", "よかった", "よくなかった")),
        )
        val hit = withStructure.match("昨日はよかった。").single { m -> m.candidates.any { it.pointId == 7L } }
        assertEquals(GrammarMatch.Via.SURFACE, hit.via)
        assertEquals("よかった", hit.matchedForm)
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
            .match("そうだって。", srsOf = { if (it == 358L) 12 else null }).single()
        assertFalse("the known candidate is gone", hit.isAmbiguous)
        assertEquals(185L, hit.primary.pointId)
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
