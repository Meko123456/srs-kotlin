package io.github.meko123456.srs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Due selection and ordering — the half of the problem the algorithm alone does not answer. */
class ReviewQueueTest {

    private data class Card(val id: String)

    private val queue = ReviewQueue(Card::id)

    private val a = Card("a")
    private val b = Card("b")
    private val c = Card("c")
    private val cards = listOf(a, b, c)

    /** A review of an item last seen on [last] whose next interval is [intervalDays]. */
    private fun review(intervalDays: Long, last: Long) =
        Review(ReviewState(repetitions = 3, intervalDays = intervalDays), lastReviewedEpochDay = last)

    @Test
    fun `an item with no record is new and therefore due`() {
        assertEquals(cards, queue.due(cards, reviews = emptyMap(), todayEpochDay = 0))
    }

    @Test
    fun `only items whose interval has elapsed come up`() {
        val reviews = mapOf(
            "b" to review(intervalDays = 1, last = 100),  // due 101
            "c" to review(intervalDays = 10, last = 100), // due 110
        )
        assertEquals(listOf(a, b), queue.due(cards, reviews, todayEpochDay = 105))
        assertEquals(listOf(a, b, c), queue.due(cards, reviews, todayEpochDay = 110))
    }

    @Test
    fun `new items lead and the rest follow most overdue first`() {
        val reviews = mapOf(
            "a" to review(intervalDays = 1, last = 100),  // due 101, most overdue
            "b" to review(intervalDays = 5, last = 100),  // due 105
        )
        // c has no record, so it leads.
        assertEquals(listOf(c, a, b), queue.due(cards, reviews, todayEpochDay = 200))
    }

    @Test
    fun `items tied on due day keep the order they were given in`() {
        val reviews = cards.associate { it.id to review(intervalDays = 5, last = 100) }
        assertEquals(cards, queue.due(cards, reviews, todayEpochDay = 200))
        assertEquals(cards.reversed(), queue.due(cards.reversed(), reviews, todayEpochDay = 200))
    }

    @Test
    fun `the count agrees with the list`() {
        val reviews = mapOf(
            "b" to review(intervalDays = 1, last = 100),
            "c" to review(intervalDays = 10, last = 100),
        )
        listOf(90L, 101L, 105L, 110L, 500L).forEach { today ->
            assertEquals(
                queue.due(cards, reviews, today).size,
                queue.dueCount(cards, reviews, today),
                "disagreed on day $today",
            )
        }
    }

    @Test
    fun `the next due day is the soonest of the scheduled items`() {
        val reviews = mapOf(
            "b" to review(intervalDays = 4, last = 100),  // 104
            "c" to review(intervalDays = 10, last = 100), // 110
        )
        // a is new, so it is not something to wait for.
        assertEquals(104L, queue.nextDueEpochDay(cards, reviews))
    }

    @Test
    fun `there is no next due day when nothing has been scheduled`() {
        assertNull(queue.nextDueEpochDay(cards, reviews = emptyMap()))
        assertNull(queue.nextDueEpochDay(items = emptyList(), reviews = emptyMap()))
    }

    @Test
    fun `recording a review stamps the day it happened`() {
        val recorded = queue.record(item = Card("a"), review = null, grade = Grade.GOOD, todayEpochDay = 200)
        assertEquals(200L, recorded.lastReviewedEpochDay)
        assertEquals(1, recorded.state.repetitions)
        assertEquals(1L, recorded.state.intervalDays)
    }

    @Test
    fun `recording carries the previous state forward`() {
        var record = queue.record(Card("a"), null, Grade.GOOD, todayEpochDay = 200)
        record = queue.record(Card("a"), record, Grade.GOOD, todayEpochDay = 201)
        assertEquals(2, record.state.repetitions)
        assertEquals(6L, record.state.intervalDays)
        assertEquals(201L, record.lastReviewedEpochDay)
    }

    @Test
    fun `a recorded item leaves the queue and comes back on its due day`() {
        val today = 200L
        val record = queue.record(Card("a"), null, Grade.GOOD, today) // interval 1
        val reviews = mapOf("a" to record)
        assertEquals(listOf(b, c), queue.due(cards, reviews, today))
        assertTrue(queue.due(cards, reviews, today + 1).contains(a))
    }

    @Test
    fun `the queue honours the scheduler it was given`() {
        val slow = ReviewQueue(Card::id, Sm2(Sm2Config(firstIntervalDays = 30)))
        val record = slow.record(Card("a"), null, Grade.GOOD, todayEpochDay = 0)
        assertEquals(30L, record.state.intervalDays)
        assertEquals(30L, slow.nextDueEpochDay(listOf(a), mapOf("a" to record)))
    }
}
