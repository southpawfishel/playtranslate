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
        // streak 5 = Adept 2. The review carries is_recurring_mastered=true,
        // but that is the Master+ opt-in, not a mastery stage.
        assertEquals(BunproLevel(BunproStage.ADEPT, 2), status.level)
        assertFalse("Adept 2 is not mastered", status.mastered)
        assertTrue(status.recurringMastered)
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

    // ── Add to reviews (write path) ─────────────────────────────────────

    @Test
    fun `add body matches the captured wire format exactly`() {
        // Captured from real traffic. reviewables is an array of [type, id]
        // TUPLES, not objects, and deck_id is an explicit null rather than
        // omitted — both are easy to get subtly wrong.
        assertEquals(
            """{"action_type":"add","deck_id":null,"reviewables":[["Vocab",7759]]}""",
            BunproClient.addToReviewsBody(listOf(7759L)),
        )
    }

    @Test
    fun `add response parses the created review`() {
        val review = PtJson.lenient
            .decodeFromString<BunproAddResponse>(ADD_RESPONSE_JSON)
            .data.first().attributes
        assertEquals(7759L, review.reviewableId)
        assertEquals("Vocab", review.reviewableType)
        assertEquals(0, review.streak)
        assertEquals(0, review.timesStudied)
    }

    @Test
    fun `a freshly added word is studied but NOT mastered`() {
        // Regression guard: the real response sets complete=true on a brand new
        // review with streak 0. Deriving `mastered` from `complete` would make
        // every just-added word render as "Mastered".
        val review = PtJson.lenient
            .decodeFromString<BunproAddResponse>(ADD_RESPONSE_JSON)
            .data.first().attributes
        val srs = BunproSrsStatus.from(review)
        assertTrue("must count as studied", srs.studied)
        assertTrue("the API really does say complete=true here", review.complete == true)
        assertFalse("but it is NOT mastered", srs.mastered)
    }

    // ── hydrate_reviewable_index (bulk SRS sync) ────────────────────────

    @Test
    fun `hydrate index parses with the EXISTING review types`() {
        // GET /reviews/hydrate_reviewable_index?reviewable_type=GrammarPoint
        // returns {"data":[<review>]} — the same envelope as the add call, so
        // no new DTOs are needed. Note what it does NOT contain: no title,
        // meaning, structure or metadata. It is the user's REVIEW RECORDS,
        // not the grammar-point catalogue.
        val parsed = PtJson.lenient.decodeFromString<BunproAddResponse>(HYDRATE_JSON)
        assertEquals(2, parsed.data.size)
        val first = parsed.data.first().attributes
        assertEquals("GrammarPoint", first.reviewableType)
        assertEquals(99L, first.reviewableId)
        assertEquals(12, first.streak)
        // Streak 12 = Master, and it is NOT flagged is_recurring_mastered —
        // more evidence that flag is the Master+ opt-in, not the stage.
        val srs = BunproSrsStatus.from(first)
        assertTrue(srs.mastered)
        assertFalse(srs.recurringMastered)
        assertEquals(BunproLevel(BunproStage.MASTER, null), srs.level)
    }

    private companion object {
        /** Two records from a real hydrate_reviewable_index response. */
        val HYDRATE_JSON = """
        {
          "data": [
            {
              "id": "37351805",
              "type": "review",
              "attributes": {
                "id": 37351805, "streak": 12,
                "next_review": "2046-07-02T05:00:00.000Z",
                "complete": true, "is_fsrs": false,
                "is_recurring_mastered": false, "review_misses": 0,
                "started_studying_at": "2025-06-23T04:00:00.000Z",
                "reviewable_id": 99, "reviewable_type": "GrammarPoint",
                "accuracy": 100, "times_studied": 12, "ghost_count": 0
              },
              "relationships": {
                "reviewable": { "data": { "id": "99", "type": "grammar_point" } }
              }
            },
            {
              "id": "63433651",
              "type": "review",
              "attributes": {
                "id": 63433651, "streak": 5,
                "next_review": "2026-08-15T22:00:00.000Z",
                "complete": true, "is_fsrs": false,
                "is_recurring_mastered": true, "review_misses": 0,
                "reviewable_id": 206, "reviewable_type": "GrammarPoint",
                "accuracy": 100, "times_studied": 5, "ghost_count": 0
              },
              "relationships": {
                "reviewable": { "data": { "id": "206", "type": "grammar_point" } }
              }
            }
          ]
        }
        """.trimIndent()

        val ADD_RESPONSE_JSON = """
        {
          "data": [
            {
              "id": "63189678",
              "type": "review",
              "attributes": {
                "id": 63189678,
                "streak": 0,
                "next_review": "2026-08-03T17:48:01.367Z",
                "complete": true,
                "is_fsrs": false,
                "is_recurring_mastered": false,
                "review_misses": 0,
                "started_studying_at": "2026-08-03T17:48:01.367Z",
                "reviewable_id": 7759,
                "reviewable_type": "Vocab",
                "default_input_type": "Cloze",
                "user_synonyms": "",
                "created_at": "2026-08-03T17:48:01.375Z",
                "updated_at": "2026-08-03T17:48:01.375Z",
                "accuracy": null,
                "times_studied": 0,
                "ghost_count": 0
              },
              "relationships": {
                "study_question": { "data": { "id": "110127", "type": "study_question" } },
                "reviewable": { "data": { "id": "7759", "type": "vocab" } }
              }
            }
          ]
        }
        """.trimIndent()

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
