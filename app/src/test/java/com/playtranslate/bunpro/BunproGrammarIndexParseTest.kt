package com.playtranslate.bunpro

import com.playtranslate.PtJson
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parses real entries from the grammar-point catalogue route
 * (`_next/data/<buildId>/en/grammar_points.json`) and pins down alias
 * extraction — the part the sentence matcher will be built on.
 */
class BunproGrammarIndexParseTest {

    private val points: List<BunproGrammarPoint> =
        PtJson.lenient.decodeFromString<BunproGrammarIndexResponse>(SAMPLE).pageProps.grammarPoints

    @Test
    fun `catalogue entries parse with their level and ordering`() {
        assertEquals(4, points.size)
        val da = points.first { it.id == 1L }
        assertEquals("だ", da.title)
        assertEquals("JLPT5", da.level)
        assertEquals(1, da.lessonId)
        val toiu = points.first { it.id == 185L }
        assertEquals("ということ", toiu.title)
        assertEquals("JLPT4", toiu.level)
    }

    @Test
    fun `aliases keep Japanese surface forms and drop romaji and tags`() {
        val aliases = points.first { it.id == 185L }.japaneseAliases()
        // Real Japanese variants survive — these are what a sentence matches on.
        assertTrue(aliases.containsAll(listOf("ということ", "と言う事", "って", "ってこと")))
        // Romaji and English descriptors are not matchable against Japanese text.
        assertFalse(aliases.contains("toiukoto"))
        assertFalse(aliases.contains("nominalizer"))
        assertFalse(aliases.any { it.contains("explanatory") })
    }

    @Test
    fun `hybrid grammar terminology is excluded, not treated as a surface form`() {
        // い-adjectives' metadata contains "い-adjective" and a bare "い".
        // The hybrid token must go: matching "い-adjective" is meaningless, and
        // it is exactly the sort of entry that looks like a surface form.
        val aliases = points.first { it.id == 24L }.japaneseAliases()
        assertFalse(aliases.any { it.contains("-") })
        assertFalse(aliases.contains("i-adjective"))
    }

    @Test
    fun `conjugation points are in the catalogue, not just lexical patterns`() {
        // Confirms grammar is not only ということ-shaped: て-form is a point in
        // its own right, so the matcher needs a morphological arm too.
        val te = points.first { it.id == 416L }
        assertEquals("Verb + て", te.title)
        assertTrue(te.japaneseAliases().contains("て"))
    }

    @Test
    fun `single particles are catalogue entries — the noise problem, in data`() {
        // だ is a grammar point whose only surface form is one kana. A lexical
        // matcher run unfiltered over a sentence would fire on entries like
        // this constantly, which is why match length/level ranking is required
        // before any of this reaches the UI.
        val da = points.first { it.id == 1L }
        assertTrue(da.japaneseAliases().all { it.length <= 2 })
    }

    private companion object {
        /** Four verbatim entries from a real catalogue response. */
        val SAMPLE = """
        {"pageProps":{"grammarPoints":[
          {"id":1,"slug":"だ","furigana":"だ","title":"だ","meaning":"To be, Is",
           "level":"JLPT5","grammar_order":1,
           "metadata":"だ, da, auxiliary verb, casual, copula","lesson_id":1},
          {"id":24,"slug":"い-adjectives","furigana":"い-Adjective","title":"い-Adjectives",
           "meaning":"Adjectives ending in い","level":"JLPT5","grammar_order":10,
           "metadata":"い-adjective, i-adjective, adjective, い, i ","lesson_id":1},
          {"id":416,"slug":"verb-て","furigana":"～て (Conjunction)","title":"Verb + て",
           "meaning":"And, Then (Linking events)","level":"JLPT5","grammar_order":61,
           "metadata":"て, って, いて, いで, んで, して, te, tte, ite, ide, nnde, nde, shite, verb, conjunction particle,",
           "lesson_id":5},
          {"id":185,"slug":"ということ","furigana":"と言（い）う事（こと）","title":"ということ",
           "meaning":"~ing, The ~ that ~ (Nominalization)","level":"JLPT4","grammar_order":151,
           "metadata":"ということ, と言う事, って, ってこと, toiukoto, tte, ttekoto, nominalizer, explanatory, clarification, interrogative",
           "lesson_id":11}
        ]}}
        """.trimIndent()
    }
}
