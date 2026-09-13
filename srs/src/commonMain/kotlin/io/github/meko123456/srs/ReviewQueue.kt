package io.github.meko123456.srs

/**
 * Turns a scheduler into the two things a review screen actually asks for: *what should I study
 * now*, and *what do I write down when the answer comes back*.
 *
 * The queue holds nothing. Items and their [Review] records stay wherever the app already keeps
 * them, and each call takes both — so this works the same over a Room query, a JSON blob or a list
 * in a test, and there is no cache to invalidate.
 *
 * ```
 * val queue = ReviewQueue(Card::id)
 * val today = Clock.System.todayIn(TimeZone.currentSystemDefault()).toEpochDays().toLong()
 *
 * val toStudy = queue.due(cards, reviews, today)
 * val updated = queue.record(reviews[card.id], Grade.GOOD, today)   // persist this
 * ```
 *
 * @param idOf how to identify an item in the [Review] map.
 * @param scheduler the algorithm to schedule by; textbook SM-2 unless told otherwise.
 */
public class ReviewQueue<T>(
    private val idOf: (T) -> String,
    private val scheduler: Scheduler = Sm2.Default,
) {

    /**
     * The items to study on [todayEpochDay], most overdue first, with never-seen items leading.
     *
     * An item with no entry in [reviews] is new, and new items are always due — that is how fresh
     * material enters the rotation without the caller tracking it separately.
     *
     * Ordering is stable: items tied on due day keep their order in [items], so a caller that wants
     * a particular order among new items (deck order, difficulty, shuffled) gets it by ordering the
     * input, not by fighting this function.
     *
     * Everything due, however much that is. Use [session] to cap the day.
     */
    public fun due(
        items: List<T>,
        reviews: Map<String, Review>,
        todayEpochDay: Long,
    ): List<T> = session(items, reviews, todayEpochDay, DailyLimits.None).items

    /**
     * The same selection as [due], capped by [limits], with the counts a screen needs to say
     * "20 of 143".
     *
     * ## What gets cut
     *
     * Reviews and new items are capped against their own budgets rather than a shared one, so a
     * backlog of due reviews never silently stops new material appearing, and a large import never
     * pushes out the reviews that are the reason the app works. Within the review budget the
     * *most overdue* survive, because the alternative is dropping exactly the items closest to being
     * forgotten.
     *
     * [DailyLimits.remainingAfter] turns a day's settings into the limits for this call, given what
     * has already been studied — the library has no idea what happened before it was asked.
     *
     * ```
     * val session = queue.session(
     *     cards, reviews, today,
     *     limits = settings.limits.remainingAfter(reviewsDoneToday, newDoneToday),
     * )
     * "${session.items.size} of ${session.available}"
     * ```
     */
    public fun session(
        items: List<T>,
        reviews: Map<String, Review>,
        todayEpochDay: Long,
        limits: DailyLimits = DailyLimits.None,
    ): StudySession<T> {
        val fresh = mutableListOf<T>()
        val scheduled = mutableListOf<Pair<T, Long>>()

        for (item in items) {
            val review = reviews[idOf(item)]
            when {
                isNew(review) -> fresh += item
                isDue(review, todayEpochDay) ->
                    scheduled += item to scheduler.dueEpochDay(review!!.state, review.lastReviewedEpochDay)
                else -> Unit
            }
        }

        // sortedBy is stable, so items tied on due day keep the order they arrived in.
        val chosenReviews = scheduled.sortedBy { (_, dueOn) -> dueOn }
            .take(limits.maxReviews)
            .map { (item, _) -> item }

        return StudySession(
            // New first, then longest overdue — the order [due] has always returned.
            items = fresh.take(limits.maxNewItems) + chosenReviews,
            dueReviews = scheduled.size,
            newItems = fresh.size,
        )
    }

    /** How many items are due on [todayEpochDay], without building the list. */
    public fun dueCount(
        items: List<T>,
        reviews: Map<String, Review>,
        todayEpochDay: Long,
    ): Int = items.count { item -> isDue(reviews[idOf(item)], todayEpochDay) }

    /**
     * The earliest day any of [items] next comes up, or `null` when there is nothing to wait for
     * (every item is new, or there are no items).
     *
     * What a "nothing due today — next review in 3 days" line is built from.
     */
    public fun nextDueEpochDay(items: List<T>, reviews: Map<String, Review>): Long? = items
        .mapNotNull { item ->
            val review = reviews[idOf(item)] ?: return@mapNotNull null
            if (review.state.isNew) null
            else scheduler.dueEpochDay(review.state, review.lastReviewedEpochDay)
        }
        .minOrNull()

    /**
     * The record to store after grading an item on [todayEpochDay].
     *
     * Pass the item's existing [Review], or `null` if it has never been reviewed.
     */
    public fun record(
        review: Review?,
        grade: Grade,
        todayEpochDay: Long,
    ): Review = Review(
        state = scheduler.schedule(review?.state ?: ReviewState(), grade),
        lastReviewedEpochDay = todayEpochDay,
    )

    /** An absent record and a never-graded one are both new. */
    private fun isNew(review: Review?): Boolean = review == null || review.state.isNew

    // Beyond being new, the scheduler decides — and it already treats a new state as due whatever
    // day is stored beside it.
    private fun isDue(review: Review?, todayEpochDay: Long): Boolean =
        review == null || scheduler.isDue(review.state, review.lastReviewedEpochDay, todayEpochDay)
}
