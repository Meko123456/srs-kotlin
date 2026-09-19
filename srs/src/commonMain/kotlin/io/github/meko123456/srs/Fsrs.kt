package io.github.meko123456.srs

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * What FSRS remembers about one item.
 *
 * Nothing in here is an ease factor, which is the point. FSRS models memory with two numbers and
 * derives the interval from them, where SM-2 stores the interval and adjusts a multiplier.
 *
 * Like [ReviewState] this is a value with no id, no timestamp and no content, so it stores next to
 * whatever the caller's item already is, and every field is a primitive. There is no
 * `@Serializable` here on purpose — this library has no dependencies and does not impose one.
 */
public data class FsrsState(
    /**
     * Days until recall of this item would fall to 90%.
     *
     * The unit is deliberately days rather than an abstract score: an item with a stability of 30
     * is one you would half-expect to have lost in about a month. Zero means never reviewed.
     */
    public val stabilityDays: Double = 0.0,
    /**
     * How hard this item is for this person, from 1 (trivial) to 10 (a nightmare).
     *
     * Not a property of the material. The same sentence is a different difficulty for two people,
     * which is why it is learned from their answers rather than set by whoever wrote the card.
     * Zero means never reviewed.
     */
    public val difficulty: Double = 0.0,
    /** Successful reviews in a row. Reset by a lapse. Reporting only; the algorithm does not read it. */
    public val repetitions: Int = 0,
    /** How many times this item has been failed. Never reset; used for leech detection. */
    public val lapses: Int = 0,
) {
    init {
        require(stabilityDays >= 0.0) { "stabilityDays must not be negative, was $stabilityDays" }
        require(difficulty >= 0.0) { "difficulty must not be negative, was $difficulty" }
        require(repetitions >= 0) { "repetitions must not be negative, was $repetitions" }
        require(lapses >= 0) { "lapses must not be negative, was $lapses" }
    }

    /** True for an item FSRS has never seen: it has no stability yet. */
    public val isNew: Boolean get() = stabilityDays <= 0.0
}

/**
 * The knobs FSRS leaves open, with the published FSRS-5 defaults.
 *
 * The one most people want is [requestRetention]. Everything else exists because FSRS is meant to be
 * *fitted* — its weights are trained on a real review history — and a library that hid them would
 * make that impossible.
 */
public data class FsrsConfig(
    /**
     * The nineteen FSRS-5 weights, in the published order.
     *
     * Defaults are the reference values, which are what you use before you have enough of somebody's
     * own history to fit better ones. Optimising them is a data problem rather than a scheduling
     * one, so it is deliberately not in this library — but the seam for it is right here.
     */
    public val weights: List<Double> = DEFAULT_WEIGHTS,
    /**
     * The probability of recall to aim for when choosing an interval, 0.9 by default.
     *
     * The single most consequential setting there is. Raising it shortens every interval and buys
     * accuracy with time; lowering it lengthens them and trades the other way. 0.9 means intervals
     * land where you would expect to remember nine cards in ten.
     */
    public val requestRetention: Double = 0.9,
    /** Upper bound on any interval, defaulting to a hundred years — effectively none. */
    public val maxIntervalDays: Long = 36_500,
    /**
     * How far an interval may be moved to stop items clumping, as a fraction of that interval.
     *
     * Zero by default, matching [Sm2Config.fuzzFactor], and it means exactly the same thing here —
     * a deterministic spread derived from the item's seed. See that field for why it exists.
     */
    public val fuzzFactor: Double = 0.0,
    /**
     * Lapses at which an item is considered a leech — one that keeps being forgotten and is usually
     * better rewritten than re-reviewed. Reported by [Fsrs.isLeech]; the library never acts on it.
     *
     * Here rather than as an argument to [Fsrs.isLeech] so it sits where [Sm2Config.leechThreshold]
     * does. A caller configuring the two schedulers should not have to learn that one takes its
     * threshold from config and the other from the call.
     */
    public val leechThreshold: Int = 8,
) {
    init {
        require(weights.size == WEIGHT_COUNT) {
            "FSRS-5 takes $WEIGHT_COUNT weights, was given ${weights.size}"
        }
        require(requestRetention > 0.0 && requestRetention < 1.0) {
            "requestRetention must be between 0 and 1 exclusive, was $requestRetention"
        }
        require(maxIntervalDays >= 1) { "maxIntervalDays must be at least 1, was $maxIntervalDays" }
        require(fuzzFactor >= 0.0 && fuzzFactor < 1.0) { "fuzzFactor must be in [0, 1), was $fuzzFactor" }
        require(leechThreshold >= 1) { "leechThreshold must be at least 1, was $leechThreshold" }
    }

    public companion object {
        /** How many weights FSRS-5 takes. */
        public const val WEIGHT_COUNT: Int = 19

        /** The published FSRS-5 reference weights. */
        public val DEFAULT_WEIGHTS: List<Double> = listOf(
            0.40255, 1.18385, 3.173, 15.69105, // initial stability, one per grade
            7.1949, 0.5345, // initial difficulty
            1.4604, // how far a grade moves difficulty
            0.0046, // how strongly difficulty reverts toward its easy baseline
            1.54575, 0.1192, 1.01925, // stability growth after a success
            1.9395, 0.11, 0.29605, 2.2698, // stability after a failure
            0.2315, 2.9898, // hard penalty, easy bonus
            0.51655, 0.6621, // same-day review terms, unused by this day-grained scheduler
        )

        /** FSRS-5 with its reference weights. */
        public val Default: FsrsConfig = FsrsConfig()
    }
}

/**
 * FSRS, the Free Spaced Repetition Scheduler — the algorithm Anki now defaults to, and SM-2's
 * successor.
 *
 * ## What it does differently
 *
 * SM-2 stores an interval and multiplies it by an ease factor that drifts with your answers. FSRS
 * stores a model of your memory — a **stability** in days and a **difficulty** — and *derives* the
 * interval from it by asking when recall would fall to [FsrsConfig.requestRetention].
 *
 * Two consequences follow, and they are the reason to prefer it:
 *
 * **It knows how late you were.** Recalling something three weeks after it was due is far stronger
 * evidence than recalling it on the day, and FSRS credits it — `elapsedDays` feeds directly into the
 * stability update through retrievability. [Sm2] has no term for this at all and treats both the
 * same.
 *
 * **Retention is a dial, not an accident.** With SM-2 the retention you end up with is whatever the
 * ease factors happen to produce. Here it is a number you set, and the intervals follow from it.
 *
 * ```
 * val fsrs = Fsrs()
 * var state = fsrs.initial()
 * state = fsrs.schedule(state, Grade.GOOD)                    // first sight
 * state = fsrs.schedule(state, Grade.GOOD, elapsedDays = 3)   // came back three days later
 * ```
 *
 * ## Honesty about this implementation
 *
 * This is an independent implementation of the published FSRS-5 formulas with the reference weights.
 * Its *behaviour* is tested — stability grows on success and falls on a lapse, difficulty moves the
 * right way, a later review is worth more than an early one, intervals move monotonically with the
 * retention you ask for — but it has **not** been checked value-for-value against Anki's
 * implementation, and nothing here should be read as a claim that it schedules identically.
 *
 * The same-day weights (17 and 18) are accepted and unused: this library is day-grained by design,
 * and sub-day scheduling is [LearningQueue]'s job.
 *
 * Instances are immutable and safe to share.
 */
public class Fsrs(
    /** The tuning this instance applies. */
    public val config: FsrsConfig = FsrsConfig.Default,
) : Scheduler<FsrsState> {

    private val w: List<Double> get() = config.weights

    override fun initial(): FsrsState = FsrsState()

    override fun isNew(state: FsrsState): Boolean = state.isNew

    override fun schedule(
        state: FsrsState,
        grade: Grade,
        elapsedDays: Long,
        itemSeed: Long,
    ): FsrsState {
        val rating = grade.rating()

        if (state.isNew) {
            return FsrsState(
                stabilityDays = initialStability(rating),
                difficulty = initialDifficulty(rating),
                repetitions = if (grade.isPass) 1 else 0,
                lapses = if (grade.isPass) 0 else 1,
            )
        }

        val retrievability = retrievability(elapsedDays, state.stabilityDays)
        val difficulty = nextDifficulty(state.difficulty, rating)
        val stability = if (grade.isPass) {
            stabilityOnRecall(state.stabilityDays, difficulty, retrievability, rating)
        } else {
            stabilityOnLapse(state.stabilityDays, difficulty, retrievability)
        }

        return FsrsState(
            stabilityDays = stability,
            difficulty = difficulty,
            repetitions = if (grade.isPass) state.repetitions + 1 else 0,
            lapses = if (grade.isPass) state.lapses else state.lapses + 1,
        )
    }

    /**
     * How likely this item is to be *forgotten* right now: one minus its retrievability.
     *
     * This is the difference the interface's default cannot express. Lateness says a
     * two-hundred-day item ten days overdue is in more trouble than a three-day item two days
     * overdue, because ten is bigger than two. The forgetting curve says the opposite, and it is
     * right — the first is still above 97% recall and the second is near 70%.
     *
     * It only changes anything when a daily cap is actually cutting items, which is exactly when
     * getting it wrong is expensive.
     */
    override fun urgency(state: FsrsState, lastReviewedEpochDay: Long, todayEpochDay: Long): Double {
        if (state.isNew) return 1.0
        return 1.0 - retrievability(todayEpochDay - lastReviewedEpochDay, state.stabilityDays)
    }

    override fun dueEpochDay(state: FsrsState, lastReviewedEpochDay: Long): Long =
        lastReviewedEpochDay + intervalDays(state)

    /**
     * The interval this state earns, in whole days.
     *
     * Public because it is the number a review screen wants to show — "next in 12 days" — and
     * deriving it from [dueEpochDay] by subtraction is a worse way to ask.
     */
    public fun intervalDays(state: FsrsState, itemSeed: Long = Scheduler.NO_SEED): Long {
        if (state.isNew) return 0
        val raw = state.stabilityDays / FACTOR *
            (config.requestRetention.pow(1.0 / DECAY) - 1.0)
        val days = raw.roundToLong().coerceIn(1L, config.maxIntervalDays)
        return IntervalSpread.apply(days, itemSeed, config.fuzzFactor, config.maxIntervalDays)
    }

    /**
     * The probability this item is still remembered after [elapsedDays], given its stability.
     *
     * Exposed because it is genuinely useful to a caller — sorting a backlog by what is closest to
     * being forgotten needs exactly this number, and it is not recoverable from the interval.
     */
    public fun retrievability(elapsedDays: Long, stabilityDays: Double): Double {
        if (stabilityDays <= 0.0) return 0.0
        val t = elapsedDays.coerceAtLeast(0).toDouble()
        return (1.0 + FACTOR * t / stabilityDays).pow(DECAY)
    }

    /**
     * Whether this item has been failed enough times to be worth rewriting rather than re-reviewing.
     *
     * Reporting only, exactly as [Sm2.isLeech] is. What to do about a leech is the app's call.
     */
    public fun isLeech(state: FsrsState): Boolean = state.lapses >= config.leechThreshold

    // ───────── the model ─────────

    private fun initialStability(rating: Int): Double =
        w[rating - 1].coerceAtLeast(MIN_STABILITY)

    private fun initialDifficulty(rating: Int): Double =
        (w[4] - exp(w[5] * (rating - 1)) + 1.0).coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)

    /**
     * Difficulty after a review, with FSRS-5's linear damping and mean reversion.
     *
     * The damping is why difficulty does not run away: a move is scaled by how much room is left
     * above it, so an already-hard item hardens slowly and an easy one has plenty of room to move.
     * The reversion then pulls everything gently back toward the difficulty a brand-new item would
     * get for an easy answer, which stops a long run of one grade from pinning an item at an
     * extreme for ever.
     */
    private fun nextDifficulty(current: Double, rating: Int): Double {
        val delta = -w[6] * (rating - 3.0)
        val damped = current + delta * (MAX_DIFFICULTY - current) / 9.0
        val reverted = w[7] * initialDifficulty(EASY_RATING) + (1.0 - w[7]) * damped
        return reverted.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)
    }

    /**
     * Stability after a successful recall.
     *
     * The shape worth knowing: growth is larger when the item is easy, when stability is still low,
     * and — the part SM-2 cannot express — when retrievability was *low*, meaning you nearly forgot
     * it and remembered anyway. Recalling something you were about to lose teaches far more than
     * recalling something you saw yesterday.
     */
    private fun stabilityOnRecall(
        stability: Double,
        difficulty: Double,
        retrievability: Double,
        rating: Int,
    ): Double {
        val hardPenalty = if (rating == HARD_RATING) w[15] else 1.0
        val easyBonus = if (rating == EASY_RATING) w[16] else 1.0
        val growth = exp(w[8]) *
            (11.0 - difficulty) *
            stability.pow(-w[9]) *
            (exp(w[10] * (1.0 - retrievability)) - 1.0) *
            hardPenalty *
            easyBonus
        return (stability * (1.0 + growth))
            .coerceIn(MIN_STABILITY, config.maxIntervalDays.toDouble())
    }

    /**
     * Stability after a lapse.
     *
     * Capped at the stability the item already had, so forgetting can never be rewarded with a
     * longer interval than remembering would have been.
     */
    private fun stabilityOnLapse(
        stability: Double,
        difficulty: Double,
        retrievability: Double,
    ): Double {
        val lapsed = w[11] *
            difficulty.pow(-w[12]) *
            ((stability + 1.0).pow(w[13]) - 1.0) *
            exp(w[14] * (1.0 - retrievability))
        return lapsed.coerceIn(MIN_STABILITY, stability)
    }

    /** FSRS grades an answer 1–4. [Grade] carries SM-2's 0–5 quality, so the two are mapped here. */
    private fun Grade.rating(): Int = when (this) {
        Grade.AGAIN -> 1
        Grade.HARD -> HARD_RATING
        Grade.GOOD -> 3
        Grade.EASY -> EASY_RATING
    }

    public companion object Default : Scheduler<FsrsState> by Fsrs(FsrsConfig.Default) {
        /**
         * The forgetting curve's exponent, and the factor that pairs with it.
         *
         * Together they are what makes stability mean "days until 90% recall": at
         * `t == stability`, `(1 + FACTOR) ^ DECAY` is 0.9 exactly.
         */
        private const val DECAY: Double = -0.5
        private const val FACTOR: Double = 19.0 / 81.0

        private const val MIN_STABILITY: Double = 0.01
        private const val MIN_DIFFICULTY: Double = 1.0
        private const val MAX_DIFFICULTY: Double = 10.0
        private const val HARD_RATING: Int = 2
        private const val EASY_RATING: Int = 4
    }
}
