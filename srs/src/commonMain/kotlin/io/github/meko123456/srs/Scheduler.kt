package io.github.meko123456.srs

/**
 * A spaced-repetition algorithm: given an item's history and how the last review went, say when to
 * show it next.
 *
 * The interface exists so the algorithm is a choice rather than a fact of the calling code. [Sm2] is
 * the implementation shipped here; a caller can supply Leitner boxes, a fixed ladder, or FSRS
 * without touching the review screen that drives it.
 *
 * ## Time
 *
 * Everything is in **epoch days** — days since 1970-01-01 — never instants. Spaced repetition is a
 * day-grained problem: an item due "today" is due whether it is looked at over breakfast or at
 * midnight, and an interval of six days means six calendar days. Longs also keep the library free of
 * a date dependency; `LocalDate.toEpochDays()` (kotlinx-datetime) or `LocalDate.toEpochDay()`
 * (java.time) is the whole conversion.
 *
 * Implementations must be pure: same inputs, same outputs, no clock reads.
 */
public interface Scheduler {

    public companion object {
        /**
         * The seed that means "schedule this item exactly, without spreading it".
         *
         * Also the default, so a caller who never asks for spreading gets textbook behaviour and a
         * caller who turns spreading on but forgets to pass a seed gets textbook behaviour too —
         * rather than every item in the collection being shifted by the same amount, which looks
         * like it is working and disperses nothing.
         */
        public const val NO_SEED: Long = 0L
    }

    /**
     * The item's state after grading a review as [grade].
     *
     * [itemSeed] identifies *which* item this is, for schedulers that spread intervals so that
     * everything studied on one day does not come back on one day. It must be stable for an item
     * and different between items; [ItemSeed] derives one from a string or a row id. Leave it out,
     * or pass [NO_SEED], and no spreading is applied.
     *
     * The seed is only ever used to pick a fixed offset. Same state, same grade and same seed
     * always give the same answer, so this stays a pure function and stays testable.
     */
    public fun schedule(state: ReviewState, grade: Grade, itemSeed: Long = NO_SEED): ReviewState

    /** The epoch day an item last reviewed on [lastReviewedEpochDay] next comes up. */
    public fun dueEpochDay(state: ReviewState, lastReviewedEpochDay: Long): Long

    /**
     * Whether the item should be reviewed on [todayEpochDay].
     *
     * Due *on or before* today, so an item that was missed for a week is still due rather than
     * silently skipped.
     *
     * A new item is always due, whatever [lastReviewedEpochDay] says. It has never been reviewed, so
     * there is no day for an interval to count from, and callers reasonably pass anything there —
     * zero, today, the day the item was created. Deciding it from the arithmetic instead would make
     * a never-seen item's availability depend on a field that means nothing yet.
     */
    public fun isDue(
        state: ReviewState,
        lastReviewedEpochDay: Long,
        todayEpochDay: Long,
    ): Boolean = state.isNew || dueEpochDay(state, lastReviewedEpochDay) <= todayEpochDay
}
