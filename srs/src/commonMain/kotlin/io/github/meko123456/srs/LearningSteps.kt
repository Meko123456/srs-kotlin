package io.github.meko123456.srs

/**
 * The short delays a new item is shown at before the [Scheduler] takes over.
 *
 * Textbook SM-2 sends a brand-new item straight to a one-day interval, which means you see something
 * for the first time and then not again until tomorrow — with nothing in between to tell you whether
 * it stuck. Anki runs new material through *learning steps* first, typically a minute and then ten,
 * and only hands it to the algorithm once it has survived them. That is a large part of why it feels
 * better on new material than SM-2 does on paper.
 *
 * Defaults are Anki's.
 */
public data class LearningConfig(
    /**
     * Delays in minutes for a new item, in order. Anki's default is one minute then ten.
     *
     * An empty list means no learning phase at all, which is textbook SM-2 — and is why an app can
     * adopt this type without changing how it behaves.
     */
    public val newStepMinutes: List<Int> = listOf(1, 10),
    /**
     * Delays for an item that has just been failed, in order.
     *
     * Separate from [newStepMinutes] because relearning something you once knew is not the same
     * problem as learning it the first time; one step is usually enough. Empty means a lapse goes
     * straight back to the scheduler, which is what SM-2 does.
     */
    public val relearningStepMinutes: List<Int> = listOf(10),
    /**
     * Whether [Grade.EASY] graduates an item immediately, skipping the steps it has left.
     *
     * On by default. Being made to sit through ten more minutes of something you answered
     * instantly is the most annoying thing a learning phase can do.
     */
    public val easyGraduatesImmediately: Boolean = true,
) {
    init {
        require(newStepMinutes.all { it >= 1 }) {
            "every learning step must be at least a minute, was $newStepMinutes"
        }
        require(relearningStepMinutes.all { it >= 1 }) {
            "every relearning step must be at least a minute, was $relearningStepMinutes"
        }
    }

    public companion object {
        /** Anki's defaults: one minute, ten minutes, then ten again after a lapse. */
        public val Default: LearningConfig = LearningConfig()

        /** No learning phase. Textbook SM-2, where a new item goes straight to its first interval. */
        public val None: LearningConfig = LearningConfig(
            newStepMinutes = emptyList(),
            relearningStepMinutes = emptyList(),
        )
    }
}

/**
 * Where an item is within its learning steps.
 *
 * Like [ReviewState] this is a value with no id and no timestamp, so it stores next to whatever the
 * caller's item already is. Two fields, both primitives.
 */
public data class LearningState(
    /** Which step comes next, counting from zero. */
    public val stepIndex: Int = 0,
    /** Whether these are relearning steps — the item was known once and has just been failed. */
    public val relearning: Boolean = false,
) {
    init {
        require(stepIndex >= 0) { "stepIndex must not be negative, was $stepIndex" }
    }
}

/** What a graded review did to an item that was still learning. */
public sealed interface LearningOutcome {
    /** Not finished: show it again in [inMinutes], with [state] as the new position. */
    public data class StillLearning(
        public val state: LearningState,
        public val inMinutes: Int,
    ) : LearningOutcome

    /**
     * Finished. Hand the item to the [Scheduler] from here.
     *
     * A graduating item has proved itself over minutes, not days, so it arrives at the scheduler as
     * a new item — which is exactly what it is as far as day-grained spacing is concerned. Whichever
     * scheduler that is: `scheduler.initial()` is the state to start it from, so this composes with
     * [Fsrs] exactly as it does with [Sm2].
     */
    public data object Graduated : LearningOutcome
}

/**
 * Runs an item through [LearningConfig]'s steps, in front of a [Scheduler].
 *
 * Deliberately a separate type rather than a change to [Scheduler]. Graduating is a different
 * problem from spacing: it happens over minutes, it is about whether something has stuck at all, and
 * it is finished within the session. Spacing happens over days and is about keeping something that
 * has already stuck. Folding one into the other would have meant widening the scheduler's time unit
 * from epoch days to something finer, and every existing caller would have paid for a phase that
 * ends within the hour.
 *
 * So the two compose instead of merging, and the caller's review loop reads:
 *
 * ```
 * val outcome = learning.review(item.learning, grade)
 * when (outcome) {
 *     is LearningOutcome.StillLearning -> item.showAgainIn(outcome.inMinutes)
 *     LearningOutcome.Graduated        -> item.review = scheduler.schedule(scheduler.initial(), grade)
 * }
 * ```
 *
 * ## Time
 *
 * Minutes here, days in the scheduler, and no clock in either. [dueEpochMinute] and [isDue] take the
 * minute an item was last seen and the minute it is now, both supplied by the caller, for the same
 * reason the scheduler takes epoch days: a pure function is testable and a function that reads a
 * clock is not.
 *
 * Instances are immutable and safe to share.
 */
public class LearningQueue(
    /** The steps this instance applies. */
    public val config: LearningConfig = LearningConfig.Default,
) {

    /** Where a brand-new item starts. */
    public fun enterNew(): LearningState = LearningState(stepIndex = 0, relearning = false)

    /** Where an item goes after being failed, if relearning steps are configured. */
    public fun enterRelearning(): LearningState = LearningState(stepIndex = 0, relearning = true)

    /** Whether [config] has any steps at all for an item in [state]'s phase. */
    public fun hasSteps(state: LearningState): Boolean = steps(state).isNotEmpty()

    /**
     * How long until an item in [state] should be shown, in minutes.
     *
     * Zero when there are no steps left, which means the item is ready to graduate rather than
     * ready to be shown.
     */
    public fun stepMinutes(state: LearningState): Int =
        steps(state).getOrNull(state.stepIndex) ?: 0

    /** The minute an item last seen at [lastSeenEpochMinute] comes up again. */
    public fun dueEpochMinute(state: LearningState, lastSeenEpochMinute: Long): Long =
        lastSeenEpochMinute + stepMinutes(state)

    /**
     * Whether the item should be shown at [nowEpochMinute].
     *
     * Due *at or after* its minute, so an item left waiting is still due rather than skipped — the
     * same rule the day-grained scheduler uses.
     */
    public fun isDue(
        state: LearningState,
        lastSeenEpochMinute: Long,
        nowEpochMinute: Long,
    ): Boolean = dueEpochMinute(state, lastSeenEpochMinute) <= nowEpochMinute

    /**
     * Grade a review of an item that is still learning.
     *
     * - [Grade.AGAIN] sends it back to the first step. Failing the second of two steps should not
     *   graduate the item next time it is answered correctly.
     * - [Grade.HARD] repeats the current step rather than advancing. Barely remembering something is
     *   not evidence it has stuck.
     * - [Grade.GOOD] advances one step, and graduates the item if that was the last.
     * - [Grade.EASY] graduates immediately when [LearningConfig.easyGraduatesImmediately] is set.
     *
     * An item with no steps configured graduates on any grade, including a failure — there is no
     * learning phase for it to be held in, and holding it nowhere forever is the only other option.
     */
    public fun review(state: LearningState, grade: Grade): LearningOutcome {
        val steps = steps(state)
        if (steps.isEmpty()) return LearningOutcome.Graduated

        if (grade == Grade.EASY && config.easyGraduatesImmediately) return LearningOutcome.Graduated

        val next = when (grade) {
            Grade.AGAIN -> 0
            Grade.HARD -> state.stepIndex
            else -> state.stepIndex + 1
        }
        if (next >= steps.size) return LearningOutcome.Graduated

        val moved = state.copy(stepIndex = next)
        return LearningOutcome.StillLearning(moved, steps[next])
    }

    private fun steps(state: LearningState): List<Int> =
        if (state.relearning) config.relearningStepMinutes else config.newStepMinutes

    public companion object {
        /**
         * Anki's defaults, ready to use without constructing anything.
         *
         * An instance rather than the companion itself, because [Sm2]'s trick of delegating an
         * interface does not apply: this type is a class, not an implementation of one.
         */
        public val Default: LearningQueue = LearningQueue(LearningConfig.Default)
    }
}
