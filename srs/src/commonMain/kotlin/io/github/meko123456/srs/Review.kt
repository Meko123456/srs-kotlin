package io.github.meko123456.srs

/**
 * One item's stored review record: its [state] plus the day it was last seen.
 *
 * The two always travel together — an interval means nothing without the day it counts from — which
 * is why the pair has a name rather than being two columns the caller keeps in step by hand.
 *
 * The defaults describe an item that has never been reviewed.
 */
public data class Review(
    public val state: ReviewState = ReviewState(),
    /** Epoch day of the last review. Ignored while [ReviewState.isNew] is true. */
    public val lastReviewedEpochDay: Long = 0,
)
