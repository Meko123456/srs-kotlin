package io.github.meko123456.srs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The algorithm itself, at the textbook defaults.
 *
 * Every case Barati and PrepParrot asserted on their own hand-rolled copies is carried over here
 * unchanged in meaning: if this file passes, the library is a drop-in for both, which is the whole
 * reason it exists.
 */
class Sm2Test {

    private val fresh = ReviewState()

    @Test
    fun `a new item starts unreviewed`() {
        assertTrue(fresh.isNew)
        assertEquals(0, fresh.repetitions)
        assertEquals(0L, fresh.intervalDays)
        assertEquals(ReviewState.DEFAULT_EASE, fresh.easeFactor)
        assertEquals(0, fresh.lapses)
    }

    @Test
    fun `the first successful review schedules one day out`() {
        val state = Sm2.schedule(fresh, Grade.GOOD)
        assertEquals(1, state.repetitions)
        assertEquals(1L, state.intervalDays)
        assertFalse(state.isNew)
    }

    @Test
    fun `the second successful review jumps to six days`() {
        val state = Sm2.schedule(Sm2.schedule(fresh, Grade.GOOD), Grade.GOOD)
        assertEquals(2, state.repetitions)
        assertEquals(6L, state.intervalDays)
    }

    @Test
    fun `from the third review the interval is multiplied by the ease factor`() {
        var state = Sm2.schedule(fresh, Grade.GOOD)
        state = Sm2.schedule(state, Grade.GOOD)
        state = Sm2.schedule(state, Grade.GOOD)
        assertEquals(3, state.repetitions)
        // GOOD leaves ease at 2.5, so six days becomes fifteen.
        assertEquals(15L, state.intervalDays)
    }

    @Test
    fun `a failure resets the streak and relearns tomorrow`() {
        var state = Sm2.schedule(fresh, Grade.GOOD)
        state = Sm2.schedule(state, Grade.GOOD)
        state = Sm2.schedule(state, Grade.AGAIN)
        assertEquals(0, state.repetitions)
        assertEquals(1L, state.intervalDays)
    }

    @Test
    fun `easy raises the ease factor and hard lowers it`() {
        assertTrue(Sm2.schedule(fresh, Grade.EASY).easeFactor > ReviewState.DEFAULT_EASE)
        assertTrue(Sm2.schedule(fresh, Grade.HARD).easeFactor < ReviewState.DEFAULT_EASE)
    }

    @Test
    fun `good leaves the ease factor untouched`() {
        assertEquals(ReviewState.DEFAULT_EASE, Sm2.schedule(fresh, Grade.GOOD).easeFactor, 1e-9)
    }

    @Test
    fun `the ease factor never falls below the floor`() {
        var state = fresh
        repeat(20) { state = Sm2.schedule(state, Grade.HARD) }
        assertTrue(state.easeFactor >= Sm2Config.Default.minEase, "ease fell to ${state.easeFactor}")
    }

    @Test
    fun `a lapse still lowers the ease factor`() {
        // The item gets easier to reach again but harder to keep: this is what makes a repeatedly
        // failed item settle at short intervals instead of bouncing straight back to weeks.
        val lapsed = Sm2.schedule(fresh, Grade.AGAIN)
        assertTrue(lapsed.easeFactor < ReviewState.DEFAULT_EASE)
    }

    @Test
    fun `a new item is always due`() {
        assertTrue(Sm2.isDue(fresh, lastReviewedEpochDay = 100, todayEpochDay = 100))
        assertTrue(Sm2.isDue(fresh, lastReviewedEpochDay = 100, todayEpochDay = 0))
    }

    @Test
    fun `an item is due only once its interval has elapsed`() {
        val state = Sm2.schedule(Sm2.schedule(fresh, Grade.GOOD), Grade.GOOD) // interval 6
        assertEquals(106L, Sm2.dueEpochDay(state, lastReviewedEpochDay = 100))
        assertFalse(Sm2.isDue(state, lastReviewedEpochDay = 100, todayEpochDay = 105))
        assertTrue(Sm2.isDue(state, lastReviewedEpochDay = 100, todayEpochDay = 106))
    }

    @Test
    fun `an item missed for weeks is still due rather than skipped`() {
        val state = Sm2.schedule(Sm2.schedule(fresh, Grade.GOOD), Grade.GOOD)
        assertTrue(Sm2.isDue(state, lastReviewedEpochDay = 100, todayEpochDay = 400))
    }

    @Test
    fun `lapses count failures and only failures`() {
        var state = fresh
        assertEquals(0, state.lapses)
        state = Sm2.schedule(state, Grade.GOOD)
        state = Sm2.schedule(state, Grade.EASY)
        state = Sm2.schedule(state, Grade.HARD)
        assertEquals(0, state.lapses, "a pass must not count as a lapse")
        state = Sm2.schedule(state, Grade.AGAIN)
        assertEquals(1, state.lapses)
        state = Sm2.schedule(state, Grade.AGAIN)
        assertEquals(2, state.lapses)
    }

    @Test
    fun `lapses survive relearning`() {
        var state = Sm2.schedule(fresh, Grade.AGAIN)
        repeat(5) { state = Sm2.schedule(state, Grade.GOOD) }
        assertEquals(1, state.lapses, "the history of a difficult item must not be erased by a good run")
    }

    @Test
    fun `repeated success grows the interval without ever shrinking it`() {
        var state = fresh
        var previous = 0L
        repeat(12) {
            state = Sm2.schedule(state, Grade.GOOD)
            assertTrue(
                state.intervalDays >= previous,
                "interval went backwards at repetition ${state.repetitions}",
            )
            previous = state.intervalDays
        }
    }

    @Test
    fun `the companion behaves exactly like a default instance`() {
        val instance = Sm2()
        var viaCompanion = fresh
        var viaInstance = fresh
        listOf(Grade.GOOD, Grade.EASY, Grade.AGAIN, Grade.HARD, Grade.GOOD).forEach { grade ->
            viaCompanion = Sm2.schedule(viaCompanion, grade)
            viaInstance = instance.schedule(viaInstance, grade)
        }
        assertEquals(viaInstance, viaCompanion)
    }
}
