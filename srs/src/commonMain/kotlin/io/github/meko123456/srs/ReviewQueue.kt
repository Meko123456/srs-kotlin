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
 * val queue = ReviewQueue(Card::id)                                 // textbook SM-2
 * val today = Clock.System.todayIn(TimeZone.currentSystemDefault()).toEpochDays().toLong()
 *
 * val toStudy = queue.due(cards, reviews, today)
 * val updated = queue.record(reviews[card.id], Grade.GOOD, today)   // persist this
 * ```
 *
 * @param idOf how to identify an item in the [Review] map.
 * @param scheduler the algorithm to schedule by. [S], the shape of an item's history, comes from it,
 *   so nothing here has to be written out: `ReviewQueue(Card::id, Fsrs())` is a
 *   `ReviewQueue<Card, FsrsState>`.
 */
public class ReviewQueue<T, S>(
    private val idOf: (T) -> String,
    private val scheduler: Scheduler<S>,
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
        reviews: Map<String, Review<S>>,
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
     * *most urgent* survive — the ones closest to being forgotten, as judged by
     * [Scheduler.urgency]. That defaults to days overdue, which is what this sorted by before the
     * method existed, so nothing changes for a scheduler without a model of memory. [Fsrs] has one
     * and overrides it, because lateness ranks a three-day item two days late below a
     * two-hundred-day item ten days late, and the forgetting curve says the opposite.
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
        reviews: Map<String, Review<S>>,
        todayEpochDay: Long,
        limits: DailyLimits = DailyLimits.None,
    ): StudySession<T> {
        val fresh = mutableListOf<T>()
        val scheduled = mutableListOf<Pair<T, Double>>()

        for (item in items) {
            val review = reviews[idOf(item)]
            when {
                isNew(review) -> fresh += item
                isDue(review, todayEpochDay) ->
                    scheduled += item to scheduler.urgency(
                        review!!.state,
                        review.lastReviewedEpochDay,
                        todayEpochDay,
                    )
                else -> Unit
            }
        }

        // sortedByDescending is stable, so items of equal urgency keep the order they arrived in.
        // With the interface's default urgency this is identical to sorting by due day, because
        // that default *is* days overdue — so nothing changes for a scheduler that does not
        // override it.
        val chosenReviews = scheduled.sortedByDescending { (_, urgency) -> urgency }
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
        reviews: Map<String, Review<S>>,
        todayEpochDay: Long,
    ): Int = items.count { item -> isDue(reviews[idOf(item)], todayEpochDay) }

    /**
     * The earliest day any of [items] next comes up, or `null` when there is nothing to wait for
     * (every item is new, or there are no items).
     *
     * What a "nothing due today — next review in 3 days" line is built from.
     */
    public fun nextDueEpochDay(items: List<T>, reviews: Map<String, Review<S>>): Long? = items
        .mapNotNull { item ->
            val review = reviews[idOf(item)] ?: return@mapNotNull null
            if (scheduler.isNew(review.state)) null
            else scheduler.dueEpochDay(review.state, review.lastReviewedEpochDay)
        }
        .minOrNull()

    /**
     * The record to store after grading [item] on [todayEpochDay].
     *
     * Pass the item's existing [Review], or `null` if it has never been reviewed.
     *
     * [item] is here rather than only its [Review] so the queue can seed interval spreading itself,
     * from the same [idOf] it already uses to key the map. Without it the caller would have to
     * remember to derive a seed and pass it on every call — and forgetting is silent, because a
     * missing seed simply means no spreading. Anything that can be derived should not be a
     * parameter somebody has to remember.
     *
     * Costs nothing when [Sm2Config.fuzzFactor] is left at zero, which is the default.
     */
    public fun record(
        item: T,
        review: Review<S>?,
        grade: Grade,
        todayEpochDay: Long,
    ): Review<S> = Review(
        state = scheduler.schedule(
            state = review?.state ?: scheduler.initial(),
            grade = grade,
            // How long it actually was, not how long it was meant to be. Negative if the caller
            // passes a day before the last review, so it is floored at zero rather than handed to
            // an algorithm as a negative age.
            elapsedDays = review?.let { (todayEpochDay - it.lastReviewedEpochDay).coerceAtLeast(0) } ?: 0,
            itemSeed = ItemSeed.of(idOf(item)),
        ),
        lastReviewedEpochDay = todayEpochDay,
    )

    /** An absent record and a never-graded one are both new. */
    private fun isNew(review: Review<S>?): Boolean = review == null || scheduler.isNew(review.state)

    // Beyond being new, the scheduler decides — and it already treats a new state as due whatever
    // day is stored beside it.
    private fun isDue(review: Review<S>?, todayEpochDay: Long): Boolean =
        review == null || scheduler.isDue(review.state, review.lastReviewedEpochDay, todayEpochDay)
}

/**
 * A queue running textbook SM-2, which is what most callers want.
 *
 * A function rather than a default argument on the constructor: [ReviewQueue]'s state type comes
 * from its scheduler, and a default cannot pin that type for one case without pinning it for all.
 * This keeps `ReviewQueue(Card::id)` meaning exactly what it always did.
 */
public fun <T> ReviewQueue(idOf: (T) -> String): ReviewQueue<T, ReviewState> =
    ReviewQueue(idOf, Sm2.Default)
