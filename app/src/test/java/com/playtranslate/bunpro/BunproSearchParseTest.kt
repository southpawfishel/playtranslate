package com.playtranslate.bunpro

import com.playtranslate.PtJson
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parses a trimmed slice of a real `search/reviewables_v1_1` response (query
 * "ということ") to lock the DTOs to the live API shape, and exercises the
 * review→item join that backs the SRS badge. The sample keeps several
 * unmodeled fields (lesson_id, register, review_misses, created_at) to prove
 * PtJson's ignoreUnknownKeys tolerance.
 */
class BunproSearchParseTest {

    private val response: BunproSearchResponse =
        PtJson.lenient.decodeFromString(SAMPLE_JSON)

    @Test
    fun `grammar point parses with its fields`() {
        val grammar = response.grammarPoints!!.data
        assertEquals(1, grammar.size)
        val gp = grammar.first()
        assertEquals("grammar_point", gp.type)
        assertEquals(345L, gp.attributes.id)
        assertEquals("ということは", gp.attributes.title)
        assertEquals("JLPT2", gp.attributes.level)
        assertEquals("That means, That is to say", gp.attributes.meaning)
    }

    @Test
    fun `studied grammar point resolves its SRS review`() {
        val section = response.grammarPoints!!
        val status = section.srsFor(section.data.first())
        assertTrue("should be marked studied", status.studied)
        assertEquals(5, status.streak)
        assertEquals(5, status.timesStudied)
        assertTrue("streak carries mastered flag", status.mastered)
        assertFalse(status.ghost)
    }

    @Test
    fun `vocab parses and carries its jmdict id`() {
        val vocab = response.vocabs!!.data
        assertEquals(1, vocab.size)
        val v = vocab.first()
        assertEquals("vocab", v.type)
        assertEquals("と言うことは", v.attributes.title)
        assertEquals(2136300L, v.attributes.jmdictId)
    }

    @Test
    fun `unstudied vocab resolves to UNSTUDIED`() {
        val section = response.vocabs!!
        val status = section.srsFor(section.data.first())
        assertFalse(status.studied)
        assertEquals(BunproSrsStatus.UNSTUDIED, status)
    }

    @Test
    fun `search request body carries the full options block and both flags`() {
        val json = BunproClient.searchRequestBody("任天堂")
        assertTrue("query present", json.contains("\"query\":\"任天堂\""))
        assertTrue("vocab flag", json.contains("\"is_searching_vocab\":true"))
        assertTrue("grammar flag even when false", json.contains("\"is_searching_grammar\":false"))
        // Regression guard: default-valued options must still be encoded.
        assertTrue("options included", json.contains("\"include_reviews\":true"))
        assertTrue("options included", json.contains("\"only_bookmarks\":false"))
    }

    private companion object {
        // Real response trimmed to one studied grammar point + one unstudied vocab.
        val SAMPLE_JSON = """
        {
          "grammar_points": {
            "data": [
              {
                "id": "345",
                "type": "grammar_point",
                "attributes": {
                  "id": 345,
                  "level": "JLPT2",
                  "lesson_id": 37,
                  "register": "一般",
                  "slug": "ということは",
                  "title": "ということは",
                  "furigana": "と言（い）う事（こと）は",
                  "meaning": "That means, That is to say",
                  "nuance_translation": "An expression which explains that which is (A)."
                },
                "relationships": {}
              }
            ],
            "included": [
              {
                "id": "61836528",
                "type": "review",
                "attributes": {
                  "id": 61836528,
                  "streak": 5,
                  "next_review": "2026-07-23T04:00:00.000Z",
                  "complete": true,
                  "is_recurring_mastered": true,
                  "review_misses": 0,
                  "created_at": "2026-07-15T06:18:48.222Z",
                  "reviewable_id": 345,
                  "reviewable_type": "GrammarPoint",
                  "accuracy": 100,
                  "times_studied": 5,
                  "ghost_count": 0
                }
              }
            ]
          },
          "vocabs": {
            "data": [
              {
                "id": "111831",
                "type": "vocab",
                "attributes": {
                  "id": 111831,
                  "title": "と言うことは",
                  "jlpt_level": "Unclassified",
                  "furigana": "と言（い）うことは",
                  "kana": "ということは",
                  "slug": "と言うことは",
                  "pitch_accent_stress": null,
                  "jmdict_id": 2136300,
                  "meaning": "that is to say, so that means"
                },
                "relationships": { "study_questions": { "data": [] } }
              }
            ],
            "included": []
          }
        }
        """.trimIndent()
    }
}
