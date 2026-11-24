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

    @Test
    fun `short fields are HTML too and go through the same cleaner`() {
        // nuance_translation, caution and the catalogue's meaning are all HTML,
        // not plain text — rendering them raw is what put literal <strong> tags
        // on screen next to a write-up that had been cleaned properly.
        assertEquals(
            "A word used to highlight the positive qualities of something.",
            BunproHtml.toPlainText(detail.nuanceTranslation),
        )
        assertEquals(
            "い-Adjective meaning 'good'",
            BunproHtml.toPlainText(detail.meaning),
        )
    }

    @Test
    fun `blank input is treated as absent`() {
        assertEquals(null, BunproHtml.toPlainTextOrNull(null))
        assertEquals(null, BunproHtml.toPlainTextOrNull("<p>  </p>"))
    }

    @Test
    fun `writeup drops study-question placeholders whole`() {
        val text = BunproWriteup(body = WRITEUP).plainText()
        // Removing only the opening <li> left the empty element behind, which
        // rendered as a run of blank lines mid-prose.
        assertFalse("blank-line run survived:\n$text", text.contains("\n\n\n"))
        assertFalse(text.contains("<"))
        assertFalse(text.contains("&nbsp;"))
    }

    @Test
    fun `writeup keeps source newlines out of the middle of sentences`() {
        val text = BunproWriteup(body = WRITEUP).plainText()
        // The body is indented HTML; those newlines are formatting, not breaks
        // the author asked for. Rendered verbatim they split sentences at
        // arbitrary points, which is exactly what the first build showed.
        assertTrue(
            "sentence was broken:\n$text",
            text.contains("To convey that '(A) was happening', or that somebody was doing (A)."),
        )
    }

    @Test
    fun `writeup keeps the breaks that were actually authored`() {
        val text = BunproWriteup(body = WRITEUP).plainText()
        // <br> and paragraph ends are real. The two paragraphs stay separated,
        // and the <br> inside the second stays a single break.
        assertTrue(text.contains("tense form いた.\n\n"))
        assertTrue(text.contains("First line.\nSecond line."))
    }

    @Test
    fun `writeup decodes the entities Bunpro actually emits`() {
        val text = BunproWriteup(body = "<p>Tanaka&#39;s &quot;rule&quot; &amp; more&hellip;</p>").plainText()
        assertEquals("Tanaka's \"rule\" & more…", text)
    }

    private companion object {
        /** Shaped like the real thing: indented source, a study-question list,
         *  an authored <br>, and entities. */
        val WRITEUP = """
            <section>
              <p>
                To convey that '(A) was happening',
                or that somebody was doing (A).
                ていた uses the conjunction particle て, and the ichidan verb いる
                in its past tense form いた.
              </p>
              <ul>
                <li data-study-question='{"id":615}'></li>
                <li data-study-question='{"id":616}'>&nbsp;</li>
              </ul>
              <p>
                First line.<br>Second line.
              </p>
            </section>
        """.trimIndent()

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
