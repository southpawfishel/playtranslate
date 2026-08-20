package com.playtranslate.bunpro

import com.playtranslate.PtJson
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parses a trimmed slice of the real いい detail page. Two things matter here:
 * the conjugated forms the catalogue index does NOT carry, and the fact that
 * the same payload also contains deliberately wrong Japanese.
 */
class BunproGrammarDetailParseTest {

    private val detail =
        PtJson.lenient.decodeFromString<BunproGrammarDetailResponse>(SAMPLE).pageProps.reviewable!!

    @Test
    fun `detail parses its display fields`() {
        assertEquals(7L, detail.id)
        assertEquals("いい", detail.title)
        assertEquals("JLPT5", detail.level)
        assertEquals("Adjective", detail.partOfSpeech)
        assertTrue(detail.caution!!.contains("frequently/often"))
    }

    @Test
    fun `structure tables yield the conjugated forms the index lacks`() {
        val forms = detail.structureForms()
        // The catalogue's metadata for いい is only "いい, 良い, よい".
        // Everything below exists ONLY in the structure tables, and a sentence
        // containing よかった matches on none of the index aliases.
        assertTrue(forms.containsAll(listOf("いい", "よくない", "よかった", "よくなかった")))
    }

    @Test
    fun `structure extraction skips non-Japanese emphasis`() {
        // <strong> is also used for emphasis in English prose elsewhere in the
        // payload; only all-Japanese runs are surface forms.
        assertFalse(detail.structureForms().any { it.contains(" ") })
        assertFalse(detail.structureForms().contains("good"))
    }

    @Test
    fun `study question answers parse, wrong answers are not exposed`() {
        val qs = PtJson.lenient
            .decodeFromString<BunproGrammarDetailResponse>(SAMPLE).pageProps.included.studyQuestions
        assertEquals(2, qs.size)
        assertEquals("よくない", qs[1].answer)
        // The payload's sibling `wrong_answers` holds intentionally malformed
        // Japanese (いいない, いくない). It is unmodelled on purpose: indexing it
        // would make the matcher validate broken grammar. This asserts the DTO
        // gives callers no route to it.
        val fields = BunproStudyQuestion::class.java.declaredFields.map { it.name }
        assertFalse(fields.any { it.contains("wrong", ignoreCase = true) })
        assertFalse(fields.any { it.contains("alternate", ignoreCase = true) })
    }

    private companion object {
        val SAMPLE = """
        {"pageProps":{
          "reviewable":{
            "id":7,"level":"JLPT5","slug":"いい","title":"いい",
            "furigana":"良（い）い","meaning":"い-Adjective meaning 'good'",
            "part_of_speech_translation":"Adjective","register_translation":"Standard",
            "nuance_translation":"A word used to highlight the <strong>positive qualities</strong> of something.",
            "caution":"よく can also mean 'frequently/often', and has several different potential kanji",
            "polite_structure":"Polite Non-Past Form: <strong>いい</strong>＋<span class='gp-popout' data-gp-id='2'>です</span><br>Polite Non-Past Negative Form: <strong>よくない</strong>＋です",
            "casual_structure":"Non-Past Form: <strong>いい</strong><br>Non-Past Negative Form: <strong>よくない</strong><br>Past Form: <strong>よかった</strong><br>Past Negative Form: <strong>よくなかった</strong>",
            "metadata":"いい, 良い, よい, ii, yoi, adjective"
          },
          "included":{"studyQuestions":[
            {"id":615,"content":"____です。","answer":"いい","kanji_answer":"いい",
             "tense":"Subjective","question_type":"cloze",
             "translation":"(It is) <strong>good</strong>.",
             "wrong_answers":{},"alternate_answers":{"よい":{"en":"…"}}},
            {"id":1522,"content":"これは____。","answer":"よくない","kanji_answer":"よくない",
             "tense":"Negative non-past","question_type":"cloze",
             "translation":"This is <strong>not good</strong>.",
             "wrong_answers":{"いいない":{"en":"check your conjugation"},
                              "いくない":{"en":"check your conjugation"}}}
          ]}
        }}
        """.trimIndent()
    }
}
