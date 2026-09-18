package io.github.meko123456.srs

import kotlin.math.roundToLong

/**
 * Moves an interval a fixed, item-specific amount so that items scheduled together stop arriving
 * together. Shared by every scheduler here, because the problem is a property of spacing rather than
 * of any one algorithm: any scheduler that computes the same interval for items with the same
 * history will pile them onto the same day for ever.
 *
 * See [Sm2Config.fuzzFactor] for the reasoning in full.
 */
internal object IntervalSpread {

    /** Shortest interval that is moved. An item due tomorrow has nowhere to go that is not today. */
    private const val MIN_SPREADABLE_DAYS: Long = 2

    /**
     * [interval], moved by a stable offset derived from [itemSeed].
     *
     * Returns [interval] untouched unless spreading is switched on, a real seed was given, and the
     * interval is long enough to have somewhere to move to.
     */
    fun apply(interval: Long, itemSeed: Long, fuzzFactor: Double, maxIntervalDays: Long): Long {
        if (fuzzFactor <= 0.0) return interval
        if (itemSeed == Scheduler.NO_SEED) return interval
        if (interval < MIN_SPREADABLE_DAYS) return interval

        // At least a day, or a percentage of a short interval rounds away to nothing.
        val reach = (interval * fuzzFactor).roundToLong().coerceAtLeast(1L)
        val offset = offsetFor(itemSeed, interval, reach)
        // Never back to one day: an item that has earned a real interval should not be dropped into
        // tomorrow's pile, which is where the failures live.
        return (interval + offset).coerceIn(MIN_SPREADABLE_DAYS, maxIntervalDays)
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
}
