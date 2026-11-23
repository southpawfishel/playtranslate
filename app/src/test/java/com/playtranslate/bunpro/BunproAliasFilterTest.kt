package com.playtranslate.bunpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every fixture is a real catalogue entry, and every rule here answers a false
 * hit measured on real captured sentences — see [BunproAliasFilter].
 */
class BunproAliasFilterTest {

    private fun point(title: String, vararg aliases: String) =
        BunproGrammarPoint(id = 1, title = title, metadata = aliases.joinToString(", "))

    private fun admit(title: String, vararg aliases: String) =
        BunproAliasFilter.admit(point(title, *aliases))

    // ── Multi-part patterns ──────────────────────────────────────────────

    @Test
    fun `a later part of a multi-part pattern never triggers`() {
        val a = admit("しか～ない ", "しか", "ない", "しかない")
        assertFalse("ない" in a.aliases)
        assertTrue("しかない" in a.aliases)
        assertFalse("the written-out pattern needs nothing more", "しかない" in a.requiresAfter)
    }

    @Test
    fun `the first part triggers only when the rest follows`() {
        val a = admit("しか～ない ", "しか", "ない", "しかない")
        val rest = a.requiresAfter.getValue("しか")
        assertTrue(BunproAliasFilter.partsFollow("これしか知らない", 4, rest))
        assertFalse(BunproAliasFilter.partsFollow("これしか", 4, rest))
    }

    @Test
    fun `plus-joined parts are parts too`() {
        val a = admit("だに + しない", "だに", "しない")
        assertFalse("しない" in a.aliases)
        assertTrue("だに" in a.requiresAfter)
    }

    @Test
    fun `a spelling variant of the first part is conditional like the part`() {
        val a = admit("全く～ない", "全くない", "まったくない", "まったく", "全く")
        assertTrue("まったく" in a.requiresAfter)
        assertFalse("まったくない" in a.requiresAfter)
    }

    @Test
    fun `optional and glossed parentheses are not parts`() {
        assertEquals(listOf("ため", "ために"), admit("ため(に)", "ため", "ために").aliases)
        assertEquals(listOf("する"), admit("する (Cost)", "する").aliases)
        assertTrue(admit("れる・られる (Potential)", "れる", "られる").requiresAfter.isEmpty())
    }

    @Test
    fun `alternatives are one part, not a sequence`() {
        val a = admit("そうに・そうな ", "そうに", "そうな")
        assertTrue(a.aliases.containsAll(listOf("そうに", "そうな")))
        assertTrue(a.requiresAfter.isEmpty())
    }

    @Test
    fun `a word-class label contributes no part`() {
        // い-Adjectives くない is ONE part (くない), so くない must survive.
        assertTrue("くない" in admit("い-Adjectives くない", "い", "くない", "ない").aliases)
    }

    // ── Dialect rewrites ─────────────────────────────────────────────────

    @Test
    fun `the standard side of a rewrite is not the point`() {
        val a = admit("のだ ⇒ ねん", "のだ", "ねん", "やねん")
        assertEquals(listOf("ねん", "やねん"), a.aliases)
    }

    @Test
    fun `a result that is just the standard form shortened is dropped`() {
        // 思う cannot be told from ordinary 思う; neither can ない.
        assertTrue(admit("と思う ⇒ 思う", "と思う", "思う").aliases.isEmpty())
        assertTrue(admit("くない ⇒ ない", "くない", "ない").aliases.isEmpty())
    }

    @Test
    fun `a result that differs from the standard form is kept`() {
        assertEquals(listOf("てる"), admit("ている ⇒ てる", "ている", "てる").aliases)
    }

    // ── Conjugation rows ─────────────────────────────────────────────────

    @Test
    fun `a conjugation row is dropped, the auxiliary named in the title stays`() {
        val a = admit(
            "れる・られる (Potential)",
            "れる", "られる", "える", "ける", "せる", "てる", "ねる", "べる", "める", "げる", "できる",
        )
        assertEquals(listOf("れる", "られる", "できる"), a.aliases)
    }

    @Test
    fun `words that merely rhyme are not a row`() {
        assertEquals(
            listOf("いい", "良い", "よい"), admit("いい", "いい", "良い", "よい").aliases,
        )
        assertEquals(
            listOf("そんなに", "こんなに", "あんなに", "どんなに"),
            admit("そんなに", "そんなに", "こんなに", "あんなに", "どんなに").aliases,
        )
    }

    // ── Structure-table forms ────────────────────────────────────────────

    @Test
    fun `a structure fragment of a longer title is dropped`() {
        val a = BunproAliasFilter.admit(
            point("ずにはすまない", "ずにはすまない"), listOf("ずには", "すまない", "では"),
        )
        assertEquals(listOf("ずにはすまない"), a.aliases)
    }

    @Test
    fun `the title minus its copula is kept unless another point is named that`() {
        val hazu = point("はずだ", "はずだ", "のはずだ")
        assertTrue("はず" in BunproAliasFilter.admit(hazu, listOf("はず")).aliases)
        val kotoda = point("ことだ", "ことだ", "事だ")
        assertFalse(
            "こと" in BunproAliasFilter.admit(kotoda, listOf("こと")) { it == "こと" }.aliases,
        )
    }
}
