package io.github.meko123456.srs

import kotlin.math.roundToLong

/**
 * The SM-2 algorithm (Piotr Woźniak, 1987), the scheduler behind Anki, SuperMemo 2 and most
 * flashcard apps written since.
 *
 * ## What it does
 *
 * Each item carries an *ease factor*, starting at 2.5. A successful review multiplies the current
 * interval by that ease; a failure sends the item back to tomorrow. The ease itself moves with how
 * hard the recall was, so an item you find easy drifts toward months apart while one you keep
 * fumbling stays close.
 *
 * The first two successful intervals are fixed (1 day, then 6) rather than computed — with an
 * interval of zero the multiplication would never leave zero.
 *
 * ```
 * val sm2 = Sm2()
 * var state = ReviewState()                    // new item
 * state = sm2.schedule(state, Grade.GOOD)      // interval 1
 * state = sm2.schedule(state, Grade.GOOD)      // interval 6
 * state = sm2.schedule(state, Grade.GOOD)      // interval 15  (6 × 2.5)
 * ```
 *
 * For the textbook defaults the companion works as a plain function holder — `Sm2.schedule(state,
 * grade)` — so nothing needs constructing for the common case. Pass a [Sm2Config] to tune it.
 *
 * Instances are immutable and safe to share.
 */
public class Sm2(
    /** The tuning this instance applies. */
    public val config: Sm2Config = Sm2Config.Default,
) : Scheduler<ReviewState> {

    override fun initial(): ReviewState = ReviewState(easeFactor = config.initialEase)

    /** New to SM-2 means never successfully reviewed: no repetitions and no interval to count from. */
    override fun isNew(state: ReviewState): Boolean = state.isNew

    /**
     * [elapsedDays] is accepted and ignored, which is SM-2 being SM-2 rather than an oversight.
     *
     * The algorithm computes the next interval from the last interval and the ease factor, and has
     * no term for how long the item was actually left. Answering a card the day it was due and
     * answering it three weeks late produce the same schedule, even though the second is far
     * stronger evidence that it stuck. [Fsrs] is the implementation that uses it.
     */
    override fun schedule(state: ReviewState, grade: Grade, elapsedDays: Long, itemSeed: Long): ReviewState {
        // Where [Sm2Config.initialEase] takes effect. A never-reviewed item has not earned an ease
        // yet — the field on a fresh [ReviewState] is only the data class default — so the configured
        // starting value applies to it. A *lapsed* item is deliberately not caught by this: its
        // interval is the lapse interval rather than zero, so it is not new, and it keeps the lower
        // ease it earned rather than being handed a fresh one on every failure.
        val startingEase = if (state.isNew) config.initialEase else state.easeFactor
        val ease = nextEaseFactor(startingEase, grade)

        if (!grade.isPass) {
            // A lapse keeps the (now lower) ease but throws away the streak: the item has to earn
            // its long intervals again, which is the whole point of the algorithm.
            return state.copy(
                repetitions = 0,
                intervalDays = config.lapseIntervalDays.coerceAtMost(config.maxIntervalDays),
                easeFactor = ease,
                lapses = state.lapses + 1,
            )
        }

        val repetitions = state.repetitions + 1
        val interval = when (repetitions) {
            1 -> config.firstIntervalDays.toDouble()
            2 -> config.secondIntervalDays.toDouble()
            else -> state.intervalDays * ease * config.intervalModifier
        }

        return state.copy(
            repetitions = repetitions,
            // Never below one day: two reviews of the same item on one day teach nothing, and a
            // modifier under 1.0 could otherwise round a short interval down to zero and wedge the
            // item permanently in today's queue.
            intervalDays = IntervalSpread.apply(
                interval = interval.roundToLong().coerceIn(1L, config.maxIntervalDays),
                itemSeed = itemSeed,
                fuzzFactor = config.fuzzFactor,
                maxIntervalDays = config.maxIntervalDays,
            ),
            easeFactor = ease,
        )
    }

    override fun dueEpochDay(state: ReviewState, lastReviewedEpochDay: Long): Long =
        lastReviewedEpochDay + state.intervalDays

    /**
     * Whether this item has been failed enough times to be worth rewriting rather than re-reviewing
     * — SM-2's own advice for an item that will not stick.
     *
     * Reporting only. Nothing in this library changes behaviour for a leech; what to do about one
     * (suspend it, tag it, split it in two) is the app's call.
     */
    public fun isLeech(state: ReviewState): Boolean = state.lapses >= config.leechThreshold

    /**
     * SM-2's ease update, `EF' = EF + (0.1 − (5 − q) × (0.08 + (5 − q) × 0.02))`, floored at
     * [Sm2Config.minEase].
     *
     * It is applied on every review including failures, which is what makes repeated lapses shorten
     * an item's future intervals rather than just postponing it.
     */
    private fun nextEaseFactor(current: Double, grade: Grade): Double {
        val q = grade.quality
        val delta = 0.1 - (5 - q) * (0.08 + (5 - q) * 0.02)
        return (current + delta).coerceAtLeast(config.minEase)
    }

    /** Textbook SM-2, ready to use without constructing anything. */
    public companion object Default : Scheduler<ReviewState> by Sm2(Sm2Config.Default)
}
