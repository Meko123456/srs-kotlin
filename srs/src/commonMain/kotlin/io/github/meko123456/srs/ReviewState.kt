package io.github.meko123456.srs

/**
 * Everything the scheduler needs to know about one item's history.
 *
 * This is a value, not a record: it carries no id, no timestamp and no content, so it can be stored
 * next to whatever the caller's item already is. The one piece of time it does not carry — *when*
 * the item was last reviewed — is passed in alongside it, because that is the part a caller usually
 * already has on their own row.
 *
 * The defaults describe a brand-new, never-reviewed item.
 *
 * There is no `@Serializable` here on purpose: this library has no dependencies, so it does not
 * force kotlinx.serialization (or any other framework) on consumers. The four fields are primitives;
 * see "Persisting review state" in the README for the five-line mapping.
 */
public data class ReviewState(
    /** Consecutive successful reviews. Reset to zero by a lapse. */
    public val repetitions: Int = 0,
    /** Days until the next review, from the last review. Zero for a new item. */
    public val intervalDays: Long = 0,
    /** Multiplier applied to the interval as the item is recalled. Falls as the item proves hard. */
    public val easeFactor: Double = DEFAULT_EASE,
    /** How many times this item has been failed. Never reset; used for leech detection. */
    public val lapses: Int = 0,
) {
    init {
        require(repetitions >= 0) { "repetitions must not be negative, was $repetitions" }
        require(intervalDays >= 0) { "intervalDays must not be negative, was $intervalDays" }
        require(easeFactor > 0) { "easeFactor must be positive, was $easeFactor" }
        require(lapses >= 0) { "lapses must not be negative, was $lapses" }
    }

    /** True for an item that has never been reviewed. New items are always due. */
    public val isNew: Boolean get() = repetitions == 0 && intervalDays == 0L

    public companion object {
        /** SM-2's starting ease factor, 2.5. */
        public const val DEFAULT_EASE: Double = 2.5
    }
}
