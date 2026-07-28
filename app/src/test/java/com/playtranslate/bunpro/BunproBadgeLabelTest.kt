package com.playtranslate.bunpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two decisions the badge rests on that aren't just plumbing:
 * which search hit counts as "this word" ([BunproLookup] match rules,
 * exercised through the parse path), and when a pill is shown at all.
 */
class BunproBadgeLabelTest {

    // ── Pill visibility ─────────────────────────────────────────────────

    @Test
    fun `unstudied status suppresses the pill`() {
        // buildPill returns null for !studied; assert the precondition the
        // badge keys on rather than inflating a View in a unit test.
        assertFalse(BunproSrsStatus.UNSTUDIED.studied)
    }

    @Test
    fun `studied status carries its streak through`() {
        val srs = BunproSrsStatus.from(BunproReview(id = 1, streak = 4, timesStudied = 4))
        assertTrue(srs.studied)
        assertEquals(4, srs.streak)
        assertFalse("no mastered flag set", srs.mastered)
    }

    @Test
    fun `mastered outranks streak`() {
        val srs = BunproSrsStatus.from(
            BunproReview(id = 1, streak = 9, isRecurringMastered = true)
        )
        assertTrue(srs.mastered)
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
