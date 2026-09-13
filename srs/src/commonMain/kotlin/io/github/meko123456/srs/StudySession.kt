package io.github.meko123456.srs

/**
 * What to study now, and what a screen needs to say about it.
 *
 * [items] is already ordered and already capped. The two counts are what was *available* before the
 * cap, which is the other half of the sentence a review screen wants to show — "20 of 143 due" needs
 * both numbers, and only the library is in a position to know the second one.
 */
public data class StudySession<T>(
    /** The items to study, ordered and within the day's limits. */
    public val items: List<T>,
    /** Reviews of already-learned items that were due today, before limits were applied. */
    public val dueReviews: Int,
    /** Never-seen items that were available today, before limits were applied. */
    public val newItems: Int,
) {
    /** Everything that was available today before limits — the denominator in "20 of 143". */
    public val available: Int get() = dueReviews + newItems

    /** Whether the day's limits held anything back. */
    public val isLimited: Boolean get() = items.size < available
}
