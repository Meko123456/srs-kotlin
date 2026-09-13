package io.github.meko123456.srs

/**
 * How much study to allow in one day.
 *
 * Every review app grows these settings eventually, because an unbounded queue after a week away is
 * how people quit: come back to four hundred due cards and the honest move is to stop using the app.
 * Capping the day is what keeps a backlog survivable.
 *
 * The two limits are separate on purpose. Reviews and new material fail differently — skipping a
 * review means forgetting something already learned, while skipping a new item means learning it
 * tomorrow instead — so they are not interchangeable and should not share a budget.
 *
 * [UNLIMITED] rather than a nullable: "no limit" is a value here, not an absence, and it keeps
 * callers from writing `?: Int.MAX_VALUE` at every use.
 */
public data class DailyLimits(
    /** Most reviews of already-learned items to hand out in a day. */
    public val maxReviews: Int = UNLIMITED,
    /** Most never-seen items to introduce in a day. */
    public val maxNewItems: Int = UNLIMITED,
) {
    init {
        require(maxReviews >= 0) { "maxReviews must not be negative, was $maxReviews" }
        require(maxNewItems >= 0) { "maxNewItems must not be negative, was $maxNewItems" }
    }

    /**
     * The headroom left after [reviewsDone] reviews and [newItemsDone] new items have already been
     * studied today.
     *
     * The library cannot know what was studied before it was asked — that count lives in the
     * caller's own store — so it is arithmetic the caller has to do, and this is the arithmetic.
     * It exists because the two easy ways to get it wrong are both handled here: subtracting from
     * [UNLIMITED] would turn "no limit" into a very large limit that quietly shrinks with every
     * review, and subtracting past zero would produce a negative limit that a naive `take` reads as
     * "none" only by accident.
     */
    public fun remainingAfter(reviewsDone: Int, newItemsDone: Int): DailyLimits = DailyLimits(
        maxReviews = remaining(maxReviews, reviewsDone),
        maxNewItems = remaining(maxNewItems, newItemsDone),
    )

    private fun remaining(limit: Int, done: Int): Int =
        if (limit == UNLIMITED) UNLIMITED else (limit - done).coerceAtLeast(0)

    public companion object {
        /** No cap. */
        public const val UNLIMITED: Int = Int.MAX_VALUE

        /** No cap on either count — what [ReviewQueue.due] uses. */
        public val None: DailyLimits = DailyLimits()
    }
}
