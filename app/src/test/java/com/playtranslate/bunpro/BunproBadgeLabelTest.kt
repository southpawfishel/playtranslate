package com.playtranslate.bunpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two decisions the badge rests on that aren't just plumbing:
 * which search hit counts as "this word" ([BunproLookup] match rules,
 * exercised through the parse path), and when a pill is shown at all.
 */
class BunproBadgeLabelTest {

    // ── Outcome distinctions the badge depends on ───────────────────────

    // ── Tolerant search vs. exact matching ──────────────────────────────

    @Test
    fun `a superstring hit is Inconclusive, never Absent`() {
        // Real captured behaviour: querying ということ returns と言うことは —
        // Bunpro appended は. Exact matching rejects it, but Bunpro clearly
        // has RELATED vocab, so claiming "not in Bunpro" would be false.
        val section = BunproSection(
            data = listOf(
                BunproItem(
                    id = "111831",
                    type = "vocab",
                    attributes = BunproItemAttributes(
                        id = 111831, title = "と言うことは",
                        kana = "ということは", slug = "と言うことは",
                    ),
                )
            )
        )
        val outcome = BunproLookup.resolve(section, "ということ")
        assertTrue(
            "superstring hit must not be reported as absent",
            outcome is BunproLookup.Outcome.Inconclusive,
        )
        // The near miss is carried so the "Maybe in Bunpro" pill can list it.
        val candidates = (outcome as BunproLookup.Outcome.Inconclusive).candidates
        assertEquals(1, candidates.size)
        assertEquals("と言うことは", candidates.first().title)
    }

    @Test
    fun `an empty result set is genuinely Absent`() {
        assertEquals(
            BunproLookup.Outcome.Absent as Any,
            BunproLookup.resolve(BunproSection(), "ホゲホゲ") as Any,
        )
    }

    @Test
    fun `an exact kana hit still resolves to Found`() {
        val section = BunproSection(
            data = listOf(
                BunproItem(
                    id = "1", type = "vocab",
                    attributes = BunproItemAttributes(id = 1, title = "肺", kana = "はい"),
                )
            )
        )
        assertTrue(BunproLookup.resolve(section, "はい") is BunproLookup.Outcome.Found)
        assertTrue(BunproLookup.resolve(section, "肺") is BunproLookup.Outcome.Found)
    }

    @Test
    fun `absent and unavailable are different values`() {
        // The whole point of the split: "not in Bunpro" is a claim we may only
        // make when a search answered. If these ever collapse, an expired token
        // or a dropped connection would render as confident absence.
        assertNotEquals(
            BunproLookup.Outcome.Absent as Any,
            BunproLookup.Outcome.Unavailable as Any,
        )
    }

    @Test
    fun `a found-but-unstudied word is still Found, not Absent`() {
        val found = BunproLookup.Outcome.Found(
            BunproLookup.WordStatus(
                vocabId = 1, slug = null, jmdictId = null, srs = BunproSrsStatus.UNSTUDIED,
            )
        )
        // Bunpro HAS the word; the user just hasn't started it. Reporting this
        // as Absent would tell the user the word doesn't exist in Bunpro.
        assertFalse(found.status.srs.studied)
        assertNotEquals(BunproLookup.Outcome.Absent as Any, found as Any)
    }

    @Test
    fun `studied status carries its streak through`() {
        val srs = BunproSrsStatus.from(BunproReview(id = 1, streak = 4, timesStudied = 4))
        assertTrue(srs.studied)
        assertEquals(4, srs.streak)
        assertFalse("no mastered flag set", srs.mastered)
    }

    // ── Streak → stage mapping ──────────────────────────────────────────

    @Test
    fun `streaks map to their Bunpro stages`() {
        fun stage(streak: Int) = BunproLevel.fromStreak(streak)
        assertEquals(BunproLevel(BunproStage.BEGINNER, 0), stage(0))
        assertEquals(BunproLevel(BunproStage.BEGINNER, 3), stage(3))
        assertEquals(BunproLevel(BunproStage.ADEPT, 1), stage(4))
        assertEquals(BunproLevel(BunproStage.ADEPT, 3), stage(6))
        assertEquals(BunproLevel(BunproStage.SEASONED, 1), stage(7))
        assertEquals(BunproLevel(BunproStage.SEASONED, 3), stage(9))
        assertEquals(BunproLevel(BunproStage.EXPERT, 1), stage(10))
        assertEquals(BunproLevel(BunproStage.EXPERT, 2), stage(11))
        assertEquals(BunproLevel(BunproStage.MASTER, null), stage(12))
        // Beyond Master stays Master rather than inventing further steps.
        assertEquals(BunproLevel(BunproStage.MASTER, null), stage(20))
    }

    @Test
    fun `is_recurring_mastered is Master-plus opt-in, NOT mastery`() {
        // The captured ということは review: streak 5 (Adept 2) WITH
        // is_recurring_mastered = true. Deriving mastery from that flag — as an
        // earlier version did — rendered this item as "Mastered".
        val srs = BunproSrsStatus.from(
            BunproReview(id = 1, streak = 5, isRecurringMastered = true)
        )
        assertFalse("streak 5 is Adept 2, not Master", srs.mastered)
        assertTrue("the flag is still surfaced, just not as mastery", srs.recurringMastered)
        assertEquals(BunproLevel(BunproStage.ADEPT, 2), srs.level)
    }

    @Test
    fun `mastery comes from the streak alone`() {
        val srs = BunproSrsStatus.from(
            BunproReview(id = 1, streak = 12, isRecurringMastered = false)
        )
        assertTrue("streak 12 is Master even with Master+ off", srs.mastered)
    }

    @Test
    fun `ghost is derived from a non-zero ghost count`() {
        assertTrue(BunproSrsStatus.from(BunproReview(id = 1, ghostCount = 2)).ghost)
        assertFalse(BunproSrsStatus.from(BunproReview(id = 1, ghostCount = 0)).ghost)
    }

    // ── Which hit is "this word" ────────────────────────────────────────

    @Test
    fun `srsFor ignores a review belonging to a different item`() {
        val section = BunproSection(
            data = listOf(item(id = 100, title = "本")),
            included = listOf(review(reviewableId = 999, streak = 7)),
        )
        // The review is for another item in the same section — must NOT leak
        // onto this one, or the badge asserts a streak the user never earned.
        assertFalse(section.srsFor(section.data.first()).studied)
    }

    @Test
    fun `srsFor binds the review with the matching reviewable id`() {
        val section = BunproSection(
            data = listOf(item(id = 100, title = "本"), item(id = 200, title = "水")),
            included = listOf(
                review(reviewableId = 200, streak = 3),
                review(reviewableId = 100, streak = 8),
            ),
        )
        assertEquals(8, section.srsFor(section.data[0]).streak)
        assertEquals(3, section.srsFor(section.data[1]).streak)
    }

    @Test
    fun `an empty section resolves to UNSTUDIED rather than throwing`() {
        val section = BunproSection()
        assertEquals(0, section.data.size)
        assertNull(BunproSrsStatus.UNSTUDIED.streak)
    }

    private fun item(id: Long, title: String) = BunproItem(
        id = id.toString(),
        type = "vocab",
        attributes = BunproItemAttributes(id = id, title = title),
    )

    private fun review(reviewableId: Long, streak: Int) = BunproIncluded(
        id = reviewableId.toString(),
        type = "review",
        attributes = BunproReview(
            id = reviewableId,
            streak = streak,
            reviewableId = reviewableId,
            reviewableType = "Vocab",
        ),
    )
}
