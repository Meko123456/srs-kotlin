package io.github.meko123456.srs

/**
 * The knobs SM-2 leaves open, with the classic values as defaults.
 *
 * Scheduling one back sits entirely in here: every number the algorithm uses is a field, so a caller
 * tuning their app's pace never has to fork the algorithm. `Sm2Config()` reproduces textbook SM-2
 * exactly, which is what makes this a drop-in for a hand-rolled implementation.
 */
public data class Sm2Config(
    /** Ease factor a new item starts at. */
    public val initialEase: Double = ReviewState.DEFAULT_EASE,
    /**
     * Floor for the ease factor.
     *
     * Without a floor, repeatedly failing an item drives ease toward zero and the interval collapses
     * to a permanent one day — the item is then unlearnable rather than merely hard.
     */
    public val minEase: Double = 1.3,
    /** Interval after the first successful review. */
    public val firstIntervalDays: Long = 1,
    /** Interval after the second successful review. */
    public val secondIntervalDays: Long = 6,
    /**
     * Upper bound on any interval, defaulting to a hundred years — effectively none.
     *
     * Useful when an item must not disappear for a decade because its ease climbed: exam material
     * with a deadline, say.
     */
    public val maxIntervalDays: Long = 36_500,
    /**
     * Global multiplier on computed intervals from the third review onward. Below 1.0 reviews more
     * often than SM-2 would, above 1.0 less often.
     */
    public val intervalModifier: Double = 1.0,
    /** Interval an item drops to after a lapse. */
    public val lapseIntervalDays: Long = 1,
    /**
     * Lapses at which an item is considered a leech — one that keeps being forgotten and is usually
     * better rewritten than re-reviewed. Reported by [Sm2.isLeech]; the library never acts on it.
     */
    public val leechThreshold: Int = 8,
) {
    init {
        require(initialEase > 0) { "initialEase must be positive, was $initialEase" }
        require(minEase > 0) { "minEase must be positive, was $minEase" }
        require(initialEase >= minEase) { "initialEase ($initialEase) must not be below minEase ($minEase)" }
        require(firstIntervalDays >= 1) { "firstIntervalDays must be at least 1, was $firstIntervalDays" }
        require(secondIntervalDays >= 1) { "secondIntervalDays must be at least 1, was $secondIntervalDays" }
        require(lapseIntervalDays >= 1) { "lapseIntervalDays must be at least 1, was $lapseIntervalDays" }
        require(maxIntervalDays >= 1) { "maxIntervalDays must be at least 1, was $maxIntervalDays" }
        require(intervalModifier > 0) { "intervalModifier must be positive, was $intervalModifier" }
        require(leechThreshold >= 1) { "leechThreshold must be at least 1, was $leechThreshold" }
    }

    public companion object {
        /** Textbook SM-2. */
        public val Default: Sm2Config = Sm2Config()
    }
}
