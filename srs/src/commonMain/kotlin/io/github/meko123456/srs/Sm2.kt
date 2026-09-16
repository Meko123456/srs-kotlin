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
) : Scheduler {

    override fun schedule(state: ReviewState, grade: Grade, itemSeed: Long): ReviewState {
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
            intervalDays = spread(interval.roundToLong(), itemSeed).coerceIn(1L, config.maxIntervalDays),
            easeFactor = ease,
        )
    }

    /**
     * Moves [interval] a fixed, item-specific amount so that items scheduled together stop arriving
     * together. See [Sm2Config.fuzzFactor] for why this exists.
     *
     * Returns [interval] untouched unless spreading is switched on, a real seed was given, and the
     * interval is long enough to have somewhere to move to.
     */
    private fun spread(interval: Long, itemSeed: Long): Long {
        if (config.fuzzFactor <= 0.0) return interval
        if (itemSeed == Scheduler.NO_SEED) return interval
        if (interval < MIN_SPREADABLE_DAYS) return interval

        // At least a day, or a percentage of a short interval rounds away to nothing.
        val reach = (interval * config.fuzzFactor).roundToLong().coerceAtLeast(1L)
        val offset = offsetFor(itemSeed, interval, reach)
        // Never back to one day: an item that has earned a real interval should not be dropped into
        // tomorrow's pile, which is where the failures live.
        return (interval + offset).coerceAtLeast(MIN_SPREADABLE_DAYS)
    }

    /**
     * A stable offset in `-reach..reach` for this item at this interval.
     *
     * The interval is mixed in as well as the seed so an item does not take the same direction at
     * every review, which would leave it permanently early or permanently late rather than merely
     * out of step with its batch.
     *
     * The mixing is SplitMix64's finaliser. It is here because the obvious alternative — using the
     * seed directly — correlates badly with the database row ids people will actually pass: ids
     * 1, 2 and 3 would take near-identical offsets and the clump would survive.
     */
    private fun offsetFor(itemSeed: Long, interval: Long, reach: Long): Long {
        var z = itemSeed * -7046029254386353131L + interval * -4658895280553007687L
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        z = z xor (z ushr 31)
        val width = 2 * reach + 1
        // `mod`, not `%`: the remainder operator keeps the sign of the dividend, so half the seeds
        // would fold onto the same offsets and the spread would be lopsided.
        return z.mod(width) - reach
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
    public companion object Default : Scheduler by Sm2(Sm2Config.Default) {
        /**
         * Shortest interval [Sm2Config.fuzzFactor] will move. An item due tomorrow has nowhere to
         * go that is not today.
         */
        private const val MIN_SPREADABLE_DAYS: Long = 2
    }
}
