package io.github.meko123456.srs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The phase in front of the scheduler: a new item shown after a minute, then after ten, and only
 * then handed over. Textbook SM-2 skips all of this and sends something you have just seen for the
 * first time straight to tomorrow.
 */
class LearningStepsTest {

    private val queue = LearningQueue.Default

    private fun graded(vararg grades: Grade): LearningOutcome {
        var outcome: LearningOutcome = LearningOutcome.StillLearning(queue.enterNew(), 1)
        for (grade in grades) {
            val state = (outcome as? LearningOutcome.StillLearning)?.state ?: return outcome
            outcome = queue.review(state, grade)
        }
        return outcome
    }

    @Test
    fun anItemWalksTheStepsAndThenGraduates() {
        assertEquals(LearningOutcome.StillLearning(LearningState(1), 10), graded(Grade.GOOD))
        assertEquals(LearningOutcome.Graduated, graded(Grade.GOOD, Grade.GOOD))
    }

    @Test
    fun failingSendsItBackToTheStart() {
        // Not back one step: failing the second of two should not mean the next correct answer
        // graduates it. The point of the phase is evidence that it stuck.
        val secondStep = LearningState(1)
        assertEquals(LearningOutcome.StillLearning(LearningState(0), 1), queue.review(secondStep, Grade.AGAIN))
        assertEquals(LearningOutcome.Graduated, graded(Grade.GOOD, Grade.AGAIN, Grade.GOOD, Grade.GOOD))
    }

    @Test
    fun barelyRememberingRepeatsTheStepRatherThanAdvancing() {
        assertEquals(LearningOutcome.StillLearning(LearningState(0), 1), queue.review(LearningState(0), Grade.HARD))
        assertEquals(LearningOutcome.StillLearning(LearningState(1), 10), queue.review(LearningState(1), Grade.HARD))
    }

    @Test
    fun answeringInstantlyGraduatesStraightAway() {
        // Being made to sit through ten more minutes of something you knew cold is the most
        // annoying thing a learning phase can do.
        assertEquals(LearningOutcome.Graduated, queue.review(queue.enterNew(), Grade.EASY))
    }

    @Test
    fun easyCanBeMadeToWalkTheStepsLikeAnythingElse() {
        val strict = LearningQueue(LearningConfig(easyGraduatesImmediately = false))
        assertEquals(
            LearningOutcome.StillLearning(LearningState(1), 10),
            strict.review(strict.enterNew(), Grade.EASY),
        )
    }

    // ───────── relearning ─────────

    @Test
    fun aLapsedItemGetsItsOwnShorterLadder() {
        val relearning = queue.enterRelearning()
        assertEquals(10, queue.stepMinutes(relearning))
        assertEquals(LearningOutcome.Graduated, queue.review(relearning, Grade.GOOD))
    }

    @Test
    fun failingWhileRelearningKeepsItRelearning() {
        val state = LearningState(stepIndex = 0, relearning = true)
        val outcome = LearningQueue(
            LearningConfig(relearningStepMinutes = listOf(5, 20)),
        ).review(LearningState(1, relearning = true), Grade.AGAIN)
        assertEquals(LearningOutcome.StillLearning(LearningState(0, relearning = true), 5), outcome)
        assertTrue(state.relearning)
    }

    // ───────── time ─────────

    @Test
    fun dueMinutesCountFromWhenItWasLastSeen() {
        val minute = 29_000_000L
        assertEquals(minute + 1, queue.dueEpochMinute(LearningState(0), minute))
        assertEquals(minute + 10, queue.dueEpochMinute(LearningState(1), minute))
    }

    @Test
    fun anItemLeftWaitingIsStillDueRatherThanSkipped() {
        val seen = 29_000_000L
        assertFalse(queue.isDue(LearningState(0), seen, seen))
        assertTrue(queue.isDue(LearningState(0), seen, seen + 1))
        assertTrue(queue.isDue(LearningState(0), seen, seen + 10_000))
    }

    // ───────── turning it off ─────────

    @Test
    fun configuringNoStepsIsTextbookSm2() {
        // The property that lets an app adopt this type without changing how it behaves.
        val none = LearningQueue(LearningConfig.None)
        assertFalse(none.hasSteps(none.enterNew()))
        Grade.entries.forEach { grade ->
            assertEquals(LearningOutcome.Graduated, none.review(none.enterNew(), grade), "grade $grade")
        }
    }

    @Test
    fun anItemWithNoStepsGraduatesEvenOnAFailure() {
        // There is nowhere to hold it, and holding it nowhere for ever is the only alternative.
        val none = LearningQueue(LearningConfig.None)
        assertEquals(LearningOutcome.Graduated, none.review(none.enterNew(), Grade.AGAIN))
    }

    // ───────── composing with the scheduler ─────────

    @Test
    fun aGraduatingItemReachesTheSchedulerAsANewOne() {
        // The whole reason the two types are separate: learning proves something stuck over minutes,
        // spacing keeps it over days, and the handover is a new item as far as spacing is concerned.
        var learning = queue.enterNew()
        var outcome = queue.review(learning, Grade.GOOD)
        learning = (outcome as LearningOutcome.StillLearning).state
        outcome = queue.review(learning, Grade.GOOD)
        assertEquals(LearningOutcome.Graduated, outcome)

        val scheduled = Sm2.schedule(ReviewState(), Grade.GOOD)
        assertEquals(1L, scheduled.intervalDays)
        assertEquals(1, scheduled.repetitions)
    }

    // ───────── validation ─────────

    @Test
    fun aStepShorterThanAMinuteIsRejected() {
        assertFailsWith<IllegalArgumentException> { LearningConfig(newStepMinutes = listOf(0, 10)) }
        assertFailsWith<IllegalArgumentException> { LearningConfig(relearningStepMinutes = listOf(-5)) }
    }

    @Test
    fun aNegativeStepIndexIsRejected() {
        assertFailsWith<IllegalArgumentException> { LearningState(stepIndex = -1) }
    }

    @Test
    fun aStepIndexPastTheEndReadsAsReadyToGraduate() {
        // Reachable if a caller shortens the step list between releases. Zero minutes means "not
        // waiting for anything", and the next review graduates it.
        assertEquals(0, queue.stepMinutes(LearningState(9)))
        assertEquals(LearningOutcome.Graduated, queue.review(LearningState(9), Grade.GOOD))
    }
}
