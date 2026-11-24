package com.playtranslate.bunpro

import com.playtranslate.ask.AskPreset
import com.playtranslate.ask.AskPresets
import com.playtranslate.ask.AskPrompts
import com.playtranslate.ask.AskQuestion
import com.playtranslate.ask.AskSentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BunproAskContext.wording]: what the grammar breakdown is told of the
 * matcher's candidates. Pins that every span is a line with its candidates
 * as the catalogue names them, that alternatives on one span are offered as
 * a choice, that the model is told to go beyond the list, and that the
 * catalogue's text cannot be read as a token of the prompt it joins.
 */
class BunproAskContextTest {

    private fun candidate(title: String, meaning: String? = null, level: String? = null, id: Long = 1) =
        GrammarMatch.GrammarCandidate(
            pointId = id, title = title, level = level, slug = null,
            meaning = meaning, isPrimaryForm = true, streak = null,
        )

    private fun match(begin: Int, end: Int, vararg candidates: GrammarMatch.GrammarCandidate) =
        GrammarMatch(begin, end, "", GrammarMatch.Via.SURFACE, candidates.toList())

    private val sentence = "忘れてしまったから"

    @Test fun `nothing matched adds nothing`() {
        assertNull(BunproAskContext.wording(sentence, emptyList()))
    }

    @Test fun `each span is a line, in sentence order, with what it covers and the catalogue's names`() {
        val wording = BunproAskContext.wording(
            sentence,
            listOf(
                match(7, 9, candidate("から①", "because", "JLPT5"), candidate("から②", "from", "JLPT5", id = 2)),
                match(2, 7, candidate("てしまう", "to do completely", "JLPT4", id = 3)),
            ),
        )!!
        val lines = wording.lines().filter { it.startsWith("- ") }
        assertEquals(
            listOf(
                "- \"てしまった\": てしまう (to do completely) [N4]",
                "- \"から\": から① (because) [N5] OR から② (from) [N5]",
            ),
            lines,
        )
    }

    @Test fun `the model is told the list is hints and to explain grammar beyond it`() {
        val wording = BunproAskContext.wording(sentence, listOf(match(7, 9, candidate("から"))))!!
        assertTrue(wording.startsWith(BunproAskContext.INTRO))
        assertTrue(wording.endsWith(BunproAskContext.INSTRUCTIONS))
        assertTrue(BunproAskContext.INTRO.contains("hints"))
        assertTrue(BunproAskContext.INSTRUCTIONS.contains("every other grammar point"))
    }

    @Test fun `the list is cut to the room it has, from the sentence's start, or left out whole`() {
        val matches = (0 until 8).map {
            match(it, it + 1, candidate("点$it", "a meaning of some length", id = it.toLong()))
        }
        val fixed = BunproAskContext.INTRO.length + BunproAskContext.INSTRUCTIONS.length + 2
        val one = "- \"忘\": 点0 (a meaning of some length)".length + 1

        val cut = BunproAskContext.wording(sentence, matches, room = fixed + one * 3)!!
        assertEquals(3, cut.lines().count { it.startsWith("- ") })
        assertTrue(cut.contains("点0") && cut.contains("点2") && !cut.contains("点3"))
        assertTrue(cut.length <= fixed + one * 3)

        assertNull(BunproAskContext.wording(sentence, matches, room = fixed + one - 1))
    }

    @Test fun `the breakdown with a full list is a question every service takes`() {
        val matches = (0 until 12).map { i ->
            val three = (1..3).map { candidate("とはいうものの$it", "x".repeat(200), "JLPT1", id = it.toLong()) }
            match(i % 9, i % 9 + 1, *three.toTypedArray())
        }
        // The room the preparer gives: the cloud's cap less the breakdown's
        // own wording, the separator and the token margin.
        val room = 1000 - AskPrompts.GRAMMAR_BREAKDOWN.length - 2 - 24
        val wording = BunproAskContext.wording(sentence, matches, room)!!
        val preset = AskPreset(AskPresets.GRAMMAR_BREAKDOWN, "t", AskPrompts.GRAMMAR_BREAKDOWN + "\n\n" + wording)
        val built = AskPrompts.build(AskQuestion.Preset(preset), AskSentence(sentence, "ja", "en"))
        assertTrue("${built.question.length}", built.question.length <= 1000)
        assertTrue(wording.lines().any { it.startsWith("- ") })
    }

    @Test fun `a span offers its best few candidates only`() {
        val many = (1..6).map { candidate("点$it", id = it.toLong()) }.toTypedArray()
        val line = BunproAskContext.wording(sentence, listOf(match(0, 2, *many)))!!
            .lines().single { it.startsWith("- ") }
        assertEquals(3, line.split(" OR ").size)
    }

    @Test fun `the catalogue's text cannot be read as a token of the prompt`() {
        val wording = BunproAskContext.wording(
            sentence, listOf(match(7, 9, candidate("から", meaning = "from {source} to {text}"))),
        )!!
        assertFalse(wording, wording.contains('{') || wording.contains('}'))

        // And so the filled prompt holds the sentence once, where the frame put it.
        val preset = AskPreset(
            AskPresets.GRAMMAR_BREAKDOWN, "Grammar breakdown", AskPrompts.GRAMMAR_BREAKDOWN + "\n\n" + wording,
        )
        val built = AskPrompts.build(AskQuestion.Preset(preset), AskSentence(sentence, "ja", "en"))
        assertTrue(built.question.contains("from source to text"))
        assertTrue(built.user.contains("- \"から\": から"))
    }
}
