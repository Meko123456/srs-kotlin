package io.github.meko123456.srs

/**
 * A spaced-repetition algorithm: given an item's history and how the last review went, say when to
 * show it next.
 *
 * The interface exists so the algorithm is a choice rather than a fact of the calling code. Two
 * implementations ship here — [Sm2] and [Fsrs] — and a caller can supply Leitner boxes or a fixed
 * ladder without touching the review screen that drives it.
 *
 * ## Why [S] is a type parameter
 *
 * Because the algorithms genuinely disagree about what an item's history *is*. SM-2 remembers a
 * repetition count, an interval and an ease factor; FSRS remembers a stability in days and a
 * difficulty, and has no concept of ease at all. A single state type would have to be the union of
 * both, carrying fields that are meaningless to whichever algorithm is running and inviting a caller
 * to read one that is.
 *
 * So the state travels with the scheduler that understands it. [Review] and [ReviewQueue] are
 * parameterised the same way, and the type is inferred from the scheduler, so
 * `ReviewQueue(Card::id, Fsrs())` needs no type arguments written out.
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
public interface Scheduler<S> {

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

    /** The state of an item that has never been reviewed. */
    public fun initial(): S

    /**
     * Whether [state] describes an item that has never been reviewed.
     *
     * On the interface rather than on the state type because the state type is now the algorithm's
     * own: SM-2 calls an item new when it has no repetitions and no interval, FSRS when it has no
     * stability yet, and neither definition belongs to the other.
     */
    public fun isNew(state: S): Boolean

    /**
     * The item's state after grading a review as [grade].
     *
     * [elapsedDays] is how long it actually was since the last review, which is not always the
     * interval that was asked for — people come back late, and sometimes early. Whether that matters
     * is one of the real differences between algorithms: SM-2 ignores it entirely and computes from
     * the interval it last handed out, so answering a card three weeks overdue counts the same as
     * answering it on time. FSRS uses it, because how likely you were to still remember is exactly
     * what it is modelling. Zero for a new item, and zero is the default so a caller that does not
     * yet care need not pass it.
     *
     * [itemSeed] identifies *which* item this is, for schedulers that spread intervals so that
     * everything studied on one day does not come back on one day. It must be stable for an item
     * and different between items; [ItemSeed] derives one from a string or a row id. Leave it out,
     * or pass [NO_SEED], and no spreading is applied.
     *
     * The seed is only ever used to pick a fixed offset. Same state, same grade and same seed
     * always give the same answer, so this stays a pure function and stays testable.
     */
    public fun schedule(
        state: S,
        grade: Grade,
        elapsedDays: Long = 0,
        itemSeed: Long = NO_SEED,
    ): S

    /** The epoch day an item last reviewed on [lastReviewedEpochDay] next comes up. */
    public fun dueEpochDay(state: S, lastReviewedEpochDay: Long): Long

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
        state: S,
        lastReviewedEpochDay: Long,
        todayEpochDay: Long,
    ): Boolean = isNew(state) || dueEpochDay(state, lastReviewedEpochDay) <= todayEpochDay

    /**
     * How badly this item needs reviewing today, for deciding what survives when a daily cap cannot
     * take everything. Higher is more urgent; the unit is the algorithm's own business.
     *
     * Only ever used for *ordering*. Nothing compares urgencies from two different schedulers, and
     * nothing reads the number out, so an implementation is free to return whatever scale makes its
     * own items rank correctly against each other.
     *
     * The default is **days overdue**, which is what [ReviewQueue] sorted by before this existed, so
     * a scheduler that ignores this method changes nothing at all. That is deliberate: lateness is
     * the best stand-in for risk available to an algorithm with no model of memory, and inventing a
     * number for one that has no basis for it would be worse than the honest proxy.
     *
     * [Fsrs] overrides it because it does have a model, and there the two genuinely differ: a
     * three-day item two days late is in far more danger than a two-hundred-day item ten days late,
     * and lateness ranks those the wrong way round.
     */
    public fun urgency(
        state: S,
        lastReviewedEpochDay: Long,
        todayEpochDay: Long,
    ): Double = (todayEpochDay - dueEpochDay(state, lastReviewedEpochDay)).toDouble()
}
