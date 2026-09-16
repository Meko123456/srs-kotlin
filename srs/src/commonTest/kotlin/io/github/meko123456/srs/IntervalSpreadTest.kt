package io.github.meko123456.srs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Spreading exists to stop a batch of items studied on one day from coming back on one day, for
 * ever. These tests pin both halves of that: the clump really does disperse, and the scheduler
 * stays a pure function while it happens.
 */
class IntervalSpreadTest {

    private val spread = Sm2(Sm2Config(fuzzFactor = 0.05))

    /** Drives an item through [reviews] passes and returns the interval it ends on. */
    private fun intervalAfter(scheduler: Scheduler, reviews: Int, seed: Long): Long {
        var state = ReviewState()
        repeat(reviews) { state = scheduler.schedule(state, Grade.GOOD, seed) }
        return state.intervalDays
    }

    @Test
    fun theDefaultConfigIsStillTextbookSm2() {
        // The library's standing promise is that Sm2Config() reproduces SM-2 exactly. Spreading is
        // opt-in precisely so that promise survives this feature.
        val plain = Sm2()
        assertEquals(1L, intervalAfter(plain, 1, seed = 12345))
        assertEquals(6L, intervalAfter(plain, 2, seed = 12345))
        assertEquals(15L, intervalAfter(plain, 3, seed = 12345))
    }

    @Test
    fun spreadingWithoutASeedChangesNothing() {
        // Turning fuzz on and forgetting the seed must not shift every item by an identical amount:
        // that would look like it was working while dispersing nothing at all.
        assertEquals(6L, intervalAfter(spread, 2, seed = Scheduler.NO_SEED))
        assertEquals(15L, intervalAfter(spread, 3, seed = Scheduler.NO_SEED))
    }

    @Test
    fun theSameItemAlwaysGetsTheSameAnswer() {
        // The whole reason the offset is derived rather than drawn at random.
        val first = spread.schedule(ReviewState(repetitions = 5, intervalDays = 40), Grade.GOOD, 99)
        repeat(20) {
            val again = spread.schedule(ReviewState(repetitions = 5, intervalDays = 40), Grade.GOOD, 99)
            assertEquals(first, again)
        }
    }

    @Test
    fun aBatchStudiedTogetherStopsArrivingTogether() {
        // The bug, stated as a test. Fifty cards added in one sitting, all graded the same way,
        // all with an identical history — the only thing telling them apart is which card they are.
        val ids = (1L..50L).map { ItemSeed.of(it) }

        val unspread = ids.map { intervalAfter(Sm2(), 3, it) }.toSet()
        assertEquals(1, unspread.size) // every one of the fifty on the same day, for ever

        val days = ids.map { intervalAfter(spread, 3, it) }
        assertTrue(days.toSet().size >= 3, "fifty cards landed on ${days.toSet().size} days: $days")
    }

    @Test
    fun theSpreadStaysWithinItsStatedReach() {
        // fuzzFactor is a promise about magnitude, not just a direction. At 40 days and 5% the
        // reach is 2 days, so nothing may land outside 38..42.
        (1L..200L).forEach { id ->
            val moved = spread.schedule(
                ReviewState(repetitions = 5, intervalDays = 16, easeFactor = 2.5),
                Grade.GOOD,
                ItemSeed.of(id),
            ).intervalDays
            assertTrue(moved in 38L..42L, "id $id landed on $moved, outside 38..42")
        }
    }

    @Test
    fun bothDirectionsActuallyGetUsed() {
        val moved = (1L..200L).map {
            spread.schedule(ReviewState(repetitions = 5, intervalDays = 16), Grade.GOOD, ItemSeed.of(it)).intervalDays
        }
        assertTrue(moved.any { it < 40 }, "nothing was ever scheduled earlier")
        assertTrue(moved.any { it > 40 }, "nothing was ever scheduled later")
    }

    @Test
    fun shortIntervalsAreLeftAlone() {
        // An item due tomorrow has nowhere to go that is not today, and today is where the
        // failures live.
        assertEquals(1L, intervalAfter(spread, 1, seed = ItemSeed.of("anything")))

        val lapsed = spread.schedule(ReviewState(repetitions = 9, intervalDays = 90), Grade.AGAIN, ItemSeed.of("x"))
        assertEquals(1L, lapsed.intervalDays)
    }

    @Test
    fun anEarnedIntervalIsNeverDroppedIntoTomorrow() {
        val tight = Sm2(Sm2Config(fuzzFactor = 0.9))
        (1L..300L).forEach { id ->
            val moved = tight.schedule(ReviewState(repetitions = 3, intervalDays = 1), Grade.GOOD, ItemSeed.of(id))
            assertTrue(moved.intervalDays >= 2, "id $id fell back to ${moved.intervalDays} days")
        }
    }

    @Test
    fun theCeilingStillHolds() {
        val capped = Sm2(Sm2Config(fuzzFactor = 0.5, maxIntervalDays = 100))
        (1L..100L).forEach { id ->
            val moved = capped.schedule(ReviewState(repetitions = 8, intervalDays = 90), Grade.GOOD, ItemSeed.of(id))
            assertTrue(moved.intervalDays <= 100, "id $id exceeded the ceiling at ${moved.intervalDays}")
        }
    }

    @Test
    fun anItemDoesNotDriftTheSameWayEveryTime() {
        // Mixing the interval into the offset as well as the seed. Without it an item would be
        // permanently early or permanently late rather than merely out of step with its batch.
        var state = ReviewState()
        val seed = ItemSeed.of("card-1")
        val directions = mutableSetOf<Int>()
        repeat(8) {
            val before = state.intervalDays
            val expected = if (state.repetitions >= 2) (before * state.easeFactor) else 0.0
            state = spread.schedule(state, Grade.GOOD, seed)
            if (expected > 0) directions += state.intervalDays.compareTo(expected.toLong())
        }
        assertTrue(directions.size > 1, "the item moved the same way at every review: $directions")
    }

    @Test
    fun theQueueSeedsSpreadingWithoutBeingAsked() {
        // The integration that makes the feature reachable. ReviewQueue already knows how to
        // identify an item, so requiring the caller to derive a seed as well would be a parameter
        // that exists only to be forgotten — and forgetting it is silent, because a missing seed
        // just means no spreading.
        data class Card(val id: String)

        val queue = ReviewQueue(Card::id, Sm2(Sm2Config(fuzzFactor = 0.05)))
        val cards = (1..50).map { Card("card-$it") }

        // Three passes each, so every card reaches a computed interval rather than a fixed one.
        val intervals = cards.map { card ->
            var review = queue.record(card, null, Grade.GOOD, todayEpochDay = 0)
            review = queue.record(card, review, Grade.GOOD, todayEpochDay = 1)
            review = queue.record(card, review, Grade.GOOD, todayEpochDay = 7)
            review.state.intervalDays
        }

        assertTrue(intervals.toSet().size >= 3, "the queue scheduled all fifty alike: ${intervals.toSet()}")
    }

    @Test
    fun theQueueIsUnaffectedWhenSpreadingIsOff() {
        data class Card(val id: String)

        val queue = ReviewQueue(Card::id, Sm2())
        val intervals = (1..10).map { n ->
            val card = Card("card-$n")
            var review = queue.record(card, null, Grade.GOOD, todayEpochDay = 0)
            review = queue.record(card, review, Grade.GOOD, todayEpochDay = 1)
            review.state.intervalDays
        }
        assertEquals(setOf(6L), intervals.toSet())
    }

    @Test
    fun aNegativeFuzzFactorIsRejected() {
        val failure = runCatching { Sm2Config(fuzzFactor = -0.1) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, "expected a rejection, got $failure")
    }

    @Test
    fun seedsAreStableDistinctAndNeverTheSentinel() {
        assertEquals(ItemSeed.of("card-42"), ItemSeed.of("card-42"))
        assertNotEquals(ItemSeed.of("card-42"), ItemSeed.of("card-43"))
        assertNotEquals(Scheduler.NO_SEED, ItemSeed.of(0L))
        assertNotEquals(Scheduler.NO_SEED, ItemSeed.of(""))
        // Consecutive row ids must not produce neighbouring offsets, which is the whole reason
        // ItemSeed.of(Long) mixes rather than passing the id through.
        val offsets = (1L..8L).map {
            spread.schedule(ReviewState(repetitions = 5, intervalDays = 16), Grade.GOOD, ItemSeed.of(it)).intervalDays
        }
        assertTrue(offsets.toSet().size >= 3, "consecutive ids barely moved apart: $offsets")
    }
}
