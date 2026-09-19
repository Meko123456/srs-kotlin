package io.github.meko123456.srs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What survives when the daily cap cannot take everything.
 *
 * The queue used to sort by due day and keep the most overdue. That is still what happens for any
 * scheduler without a model of memory, because lateness is the best stand-in for risk such a
 * scheduler has. FSRS has a model, and it disagrees in a way that matters.
 */
class UrgencyTest {

    private data class Card(val id: String)

    private val today = 1_000L

    // ───────── the default is the old behaviour, exactly

    @Test
    fun `without an override urgency is days overdue`() {
        val sm2 = Sm2()
        val state = ReviewState(repetitions = 3, intervalDays = 10)
        // Last reviewed 30 days ago with a 10-day interval: due on day 980, so 20 days overdue.
        assertEquals(20.0, sm2.urgency(state, lastReviewedEpochDay = today - 30, todayEpochDay = today))
        assertEquals(0.0, sm2.urgency(state, lastReviewedEpochDay = today - 10, todayEpochDay = today))
    }

    @Test
    fun `the cap still keeps the most overdue under SM-2`() {
        val queue = ReviewQueue(Card::id, Sm2())
        val cards = (1..4).map { Card("c$it") }
        val reviews = cards.mapIndexed { index, card ->
            // c1 is 40 days overdue, c4 only 10.
            card.id to Review(ReviewState(repetitions = 3, intervalDays = 10), today - 50 + index * 10)
        }.toMap()

        val session = queue.session(cards, reviews, today, DailyLimits(maxReviews = 2, maxNewItems = 0))

        assertEquals(listOf(cards[0], cards[1]), session.items)
        assertEquals(4, session.dueReviews)
    }

    @Test
    fun `ties keep the order they arrived in`() {
        val queue = ReviewQueue(Card::id, Sm2())
        val cards = (1..5).map { Card("c$it") }
        val reviews = cards.associate {
            it.id to Review(ReviewState(repetitions = 3, intervalDays = 10), today - 30)
        }

        val session = queue.session(cards, reviews, today, DailyLimits(maxReviews = 3, maxNewItems = 0))
        assertEquals(cards.take(3), session.items)
    }

    // ───────── FSRS knows better, and says so

    @Test
    fun `lateness and risk of forgetting disagree — and FSRS follows the risk`() {
        // The case from the issue. A three-day item two days late is in far more trouble than a
        // two-hundred-day item ten days late, even though the second is five times later.
        val fsrs = Fsrs()
        val fragile = FsrsState(stabilityDays = 3.0, difficulty = 6.0, repetitions = 2)
        val settled = FsrsState(stabilityDays = 200.0, difficulty = 4.0, repetitions = 9)

        val fragileUrgency = fsrs.urgency(fragile, today - 5, today)   // due 2 days ago
        val settledUrgency = fsrs.urgency(settled, today - 210, today) // due 10 days ago

        assertTrue(
            fragileUrgency > settledUrgency,
            "the fragile item should win: $fragileUrgency vs $settledUrgency",
        )
        // And the ordering the old rule would have produced is the opposite one.
        assertTrue((today - 210) < (today - 5), "the settled item really is the later of the two")
    }

    @Test
    fun `the cap keeps what is closest to being forgotten`() {
        val queue = ReviewQueue(Card::id, Fsrs())
        val fragile = Card("fragile")
        val settled = Card("settled")
        val reviews = mapOf(
            fragile.id to Review(FsrsState(stabilityDays = 3.0, difficulty = 6.0, repetitions = 2), today - 5),
            settled.id to Review(FsrsState(stabilityDays = 200.0, difficulty = 4.0, repetitions = 9), today - 210),
        )

        // Deliberately listed later-first, so passing cannot come from input order.
        val session = queue.session(
            listOf(settled, fragile), reviews, today,
            DailyLimits(maxReviews = 1, maxNewItems = 0),
        )

        assertEquals(listOf(fragile), session.items)
        assertEquals(2, session.dueReviews)
    }

    @Test
    fun `urgency rises as an item is left longer`() {
        val fsrs = Fsrs()
        val state = FsrsState(stabilityDays = 10.0, difficulty = 5.0, repetitions = 3)
        val curve = listOf(10L, 20L, 40L, 200L).map { fsrs.urgency(state, today - it, today) }

        assertEquals(curve.sorted(), curve, "leaving an item longer must not make it less urgent: $curve")
        assertTrue(curve.all { it in 0.0..1.0 })
    }

    @Test
    fun `a never-seen item is as urgent as it gets`() {
        // Not used for ordering by the queue, which handles new items on their own budget, but the
        // number should not be nonsense if somebody reads it.
        assertEquals(1.0, Fsrs().urgency(Fsrs().initial(), today, today))
    }
}
