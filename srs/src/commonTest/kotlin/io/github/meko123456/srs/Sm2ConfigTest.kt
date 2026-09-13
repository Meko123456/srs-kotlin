package io.github.meko123456.srs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The knobs — the part a hand-rolled copy does not have. */
class Sm2ConfigTest {

    private val fresh = ReviewState()

    private fun Sm2.run(vararg grades: Grade): ReviewState {
        var state = fresh
        grades.forEach { state = schedule(state, it) }
        return state
    }

    @Test
    fun `the defaults reproduce textbook SM-2`() {
        assertEquals(2.5, Sm2Config.Default.initialEase)
        assertEquals(1.3, Sm2Config.Default.minEase)
        assertEquals(1L, Sm2Config.Default.firstIntervalDays)
        assertEquals(6L, Sm2Config.Default.secondIntervalDays)
        assertEquals(1.0, Sm2Config.Default.intervalModifier)
    }

    @Test
    fun `the first two intervals can be moved`() {
        val sm2 = Sm2(Sm2Config(firstIntervalDays = 2, secondIntervalDays = 10))
        assertEquals(2L, sm2.run(Grade.GOOD).intervalDays)
        assertEquals(10L, sm2.run(Grade.GOOD, Grade.GOOD).intervalDays)
    }

    @Test
    fun `the interval modifier scales reviews from the third onward`() {
        val half = Sm2(Sm2Config(intervalModifier = 0.5))
        // The fixed first two are untouched...
        assertEquals(1L, half.run(Grade.GOOD).intervalDays)
        assertEquals(6L, half.run(Grade.GOOD, Grade.GOOD).intervalDays)
        // ...and the computed third is 6 x 2.5 x 0.5 = 7.5, rounded to 8.
        assertEquals(8L, half.run(Grade.GOOD, Grade.GOOD, Grade.GOOD).intervalDays)
    }

    @Test
    fun `the maximum interval caps growth`() {
        val capped = Sm2(Sm2Config(maxIntervalDays = 10))
        var state = fresh
        repeat(10) { state = capped.schedule(state, Grade.EASY) }
        assertEquals(10L, state.intervalDays)
    }

    @Test
    fun `an interval never rounds down to zero`() {
        // A tiny modifier would otherwise produce a zero-day interval, which wedges the item in
        // today's queue for good.
        val tiny = Sm2(Sm2Config(intervalModifier = 0.001))
        var state = fresh
        repeat(6) { state = tiny.schedule(state, Grade.GOOD) }
        assertTrue(state.intervalDays >= 1L, "interval collapsed to ${state.intervalDays}")
    }

    @Test
    fun `the lapse interval can be moved`() {
        val sm2 = Sm2(Sm2Config(lapseIntervalDays = 3))
        assertEquals(3L, sm2.run(Grade.GOOD, Grade.GOOD, Grade.AGAIN).intervalDays)
    }

    @Test
    fun `a configured starting ease is what a new item begins from`() {
        val hard = Sm2(Sm2Config(initialEase = 1.8))
        // GOOD leaves the ease where it is, so the first review exposes the starting value.
        assertEquals(1.8, hard.schedule(fresh, Grade.GOOD).easeFactor, 1e-9)
        // EASY moves it up from there rather than from the library default.
        assertEquals(1.9, hard.schedule(fresh, Grade.EASY).easeFactor, 1e-9)
        // ...and the default config still starts where SM-2 says.
        assertEquals(ReviewState.DEFAULT_EASE, Sm2().schedule(fresh, Grade.GOOD).easeFactor, 1e-9)
    }

    @Test
    fun `a configured starting ease changes the intervals that follow`() {
        val hard = Sm2(Sm2Config(initialEase = 1.8))
        var state = fresh
        repeat(3) { state = hard.schedule(state, Grade.GOOD) }
        // Six days times 1.8 rather than times 2.5.
        assertEquals(11L, state.intervalDays)
        assertEquals(15L, Sm2().run(Grade.GOOD, Grade.GOOD, Grade.GOOD).intervalDays)
    }

    @Test
    fun `a lapsed item keeps the ease it earned rather than starting over`() {
        val hard = Sm2(Sm2Config(initialEase = 2.4))
        var state = hard.schedule(fresh, Grade.GOOD)
        state = hard.schedule(state, Grade.AGAIN)
        val afterLapse = state.easeFactor
        assertTrue(afterLapse < 2.4, "the lapse should have lowered the ease, was $afterLapse")

        // The item is no longer new - its interval is the lapse interval, not zero - so the next
        // review continues from the lowered ease instead of being handed the starting one again.
        assertFalse(state.isNew)
        state = hard.schedule(state, Grade.GOOD)
        assertEquals(afterLapse, state.easeFactor, 1e-9)
    }

    @Test
    fun `a raised ease floor keeps hard items from collapsing`() {
        val sm2 = Sm2(Sm2Config(minEase = 2.0))
        var state = fresh
        repeat(20) { state = sm2.schedule(state, Grade.HARD) }
        assertEquals(2.0, state.easeFactor, 1e-9)
    }

    @Test
    fun `leeches are reported at the configured threshold`() {
        val sm2 = Sm2(Sm2Config(leechThreshold = 2))
        var state = fresh
        assertFalse(sm2.isLeech(state))
        state = sm2.schedule(state, Grade.AGAIN)
        assertFalse(sm2.isLeech(state), "one lapse is not a leech")
        state = sm2.schedule(state, Grade.AGAIN)
        assertTrue(sm2.isLeech(state))
    }

    @Test
    fun `a config that contradicts itself is rejected`() {
        assertFailsWith<IllegalArgumentException> { Sm2Config(initialEase = 1.0, minEase = 2.0) }
        assertFailsWith<IllegalArgumentException> { Sm2Config(minEase = 0.0) }
        assertFailsWith<IllegalArgumentException> { Sm2Config(firstIntervalDays = 0) }
        assertFailsWith<IllegalArgumentException> { Sm2Config(intervalModifier = 0.0) }
        assertFailsWith<IllegalArgumentException> { Sm2Config(maxIntervalDays = 0) }
        assertFailsWith<IllegalArgumentException> { Sm2Config(leechThreshold = 0) }
    }

    @Test
    fun `an impossible review state is rejected`() {
        assertFailsWith<IllegalArgumentException> { ReviewState(repetitions = -1) }
        assertFailsWith<IllegalArgumentException> { ReviewState(intervalDays = -1) }
        assertFailsWith<IllegalArgumentException> { ReviewState(easeFactor = 0.0) }
        assertFailsWith<IllegalArgumentException> { ReviewState(lapses = -1) }
    }
}
