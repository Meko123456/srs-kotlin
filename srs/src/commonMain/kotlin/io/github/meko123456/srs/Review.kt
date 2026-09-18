package io.github.meko123456.srs

/**
 * One item's stored review record: its [state] plus the day it was last seen.
 *
 * The two always travel together — an interval means nothing without the day it counts from — which
 * is why the pair has a name rather than being two columns the caller keeps in step by hand.
 *
 * [S] is whatever state type the scheduler in use understands — [ReviewState] for [Sm2],
 * [FsrsState] for [Fsrs]. There is no default, because there is no longer one state type to default
 * to; `Scheduler.initial()` is where a never-reviewed item comes from.
 */
public data class Review<S>(
    public val state: S,
    /** Epoch day of the last review. Ignored while the scheduler calls the state new. */
    public val lastReviewedEpochDay: Long = 0,
)
