package io.github.meko123456.srs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Capping the day, and the counts a screen shows while doing it. */
class StudySessionTest {

    private data class Card(val id: String)

    private val queue = ReviewQueue(Card::id)

    private val n1 = Card("n1")
    private val n2 = Card("n2")
    private val n3 = Card("n3")
    private val r1 = Card("r1")
    private val r2 = Card("r2")
    private val r3 = Card("r3")

    /** Input order deliberately differs from due order, so the two cannot be confused. */
    private val cards = listOf(n1, r3, n2, r1, n3, r2)

    private fun review(dueOn: Long) =
        Review(ReviewState(repetitions = 3, intervalDays = dueOn - 100), lastReviewedEpochDay = 100)

    private val reviews = mapOf(
        "r1" to review(dueOn = 101),
        "r2" to review(dueOn = 105),
        "r3" to review(dueOn = 110),
    )

    private val today = 200L

    @Test
    fun `an uncapped session is exactly what due returns`() {
        val session = queue.session(cards, reviews, today)
        assertEquals(queue.due(cards, reviews, today), session.items)
        assertEquals(listOf(n1, n2, n3, r1, r2, r3), session.items)
    }

    @Test
    fun `the counts report what was available before any cap`() {
        val session = queue.session(cards, reviews, today, DailyLimits(maxReviews = 1, maxNewItems = 1))
        assertEquals(3, session.dueReviews)
        assertEquals(3, session.newItems)
        assertEquals(6, session.available)
        assertEquals(2, session.items.size)
        assertEquals(queue.dueCount(cards, reviews, today), session.available)
    }

    @Test
    fun `new items are capped on their own budget and taken in the order given`() {
        val session = queue.session(cards, reviews, today, DailyLimits(maxNewItems = 2))
        assertEquals(listOf(n1, n2, r1, r2, r3), session.items)
    }

    @Test
    fun `the most overdue reviews survive the cap`() {
        val session = queue.session(cards, reviews, today, DailyLimits(maxReviews = 2))
        // r1 and r2 were due before r3, whatever order they sit in the input.
        assertEquals(listOf(n1, n2, n3, r1, r2), session.items)
    }

    @Test
    fun `the two budgets are independent`() {
        // A wall of due reviews must not stop new material appearing...
        val noReviews = queue.session(cards, reviews, today, DailyLimits(maxReviews = 0))
        assertEquals(listOf(n1, n2, n3), noReviews.items)
        assertEquals(3, noReviews.dueReviews, "the reviews were still counted as available")

        // ...and a pile of new material must not push out the reviews.
        val noNew = queue.session(cards, reviews, today, DailyLimits(maxNewItems = 0))
        assertEquals(listOf(r1, r2, r3), noNew.items)
        assertEquals(3, noNew.newItems)
    }

    @Test
    fun `a session can be capped to nothing`() {
        val session = queue.session(cards, reviews, today, DailyLimits(maxReviews = 0, maxNewItems = 0))
        assertTrue(session.items.isEmpty())
        assertEquals(6, session.available)
        assertTrue(session.isLimited)
    }

    @Test
    fun `isLimited says whether anything was held back`() {
        assertFalse(queue.session(cards, reviews, today).isLimited)
        assertFalse(queue.session(cards, reviews, today, DailyLimits(maxReviews = 99, maxNewItems = 99)).isLimited)
        assertTrue(queue.session(cards, reviews, today, DailyLimits(maxReviews = 2)).isLimited)
    }

    @Test
    fun `an item that is not due is not counted as available`() {
        // On day 105, r3 is not yet due.
        val session = queue.session(cards, reviews, todayEpochDay = 105)
        assertEquals(2, session.dueReviews)
        assertEquals(5, session.available)
        assertFalse(session.items.contains(r3))
    }

    // ───────── carrying the day's budget ─────────

    @Test
    fun `remainingAfter subtracts what has already been studied`() {
        val settings = DailyLimits(maxReviews = 100, maxNewItems = 20)
        val left = settings.remainingAfter(reviewsDone = 40, newItemsDone = 5)
        assertEquals(60, left.maxReviews)
        assertEquals(15, left.maxNewItems)
    }

    @Test
    fun `remainingAfter never goes negative`() {
        val left = DailyLimits(maxReviews = 10, maxNewItems = 10).remainingAfter(99, 99)
        assertEquals(0, left.maxReviews)
        assertEquals(0, left.maxNewItems)
    }

    @Test
    fun `remainingAfter leaves an unlimited budget unlimited`() {
        // Subtracting from the sentinel would turn "no limit" into a limit that shrinks all day.
        val left = DailyLimits.None.remainingAfter(500, 500)
        assertEquals(DailyLimits.UNLIMITED, left.maxReviews)
        assertEquals(DailyLimits.UNLIMITED, left.maxNewItems)
        assertEquals(queue.due(cards, reviews, today), queue.session(cards, reviews, today, left).items)
    }

    @Test
    fun `a day already spent yields nothing`() {
        val left = DailyLimits(maxReviews = 2, maxNewItems = 1).remainingAfter(2, 1)
        assertTrue(queue.session(cards, reviews, today, left).items.isEmpty())
    }

    @Test
    fun `a negative limit is rejected`() {
        assertFailsWith<IllegalArgumentException> { DailyLimits(maxReviews = -1) }
        assertFailsWith<IllegalArgumentException> { DailyLimits(maxNewItems = -1) }
    }
}
