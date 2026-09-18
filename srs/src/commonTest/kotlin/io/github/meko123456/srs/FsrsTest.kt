package io.github.meko123456.srs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * FSRS is tested on its behaviour rather than against a reference implementation's numbers.
 *
 * That is a deliberate limit and worth stating: nothing here proves this schedules identically to
 * Anki. What it proves is that the model behaves like a model of memory — stability grows when you
 * remember and falls when you do not, difficulty moves the right way, a review you nearly failed
 * teaches more than an easy one, and the retention dial actually turns.
 *
 * Every one of these corresponds to a claim made in the KDoc, which is the point: the documentation
 * says what the algorithm does, and these are what stop that from becoming fiction.
 */
class FsrsTest {

    private val fsrs = Fsrs()

    /** A settled item: reviewed a few times, mid difficulty. */
    private fun settled(stability: Double = 20.0, difficulty: Double = 5.0) =
        FsrsState(stabilityDays = stability, difficulty = difficulty, repetitions = 3)

    // ───────── first sight ─────────

    @Test
    fun aNewItemHasNoStabilityAndNoDifficulty() {
        val fresh = fsrs.initial()
        assertTrue(fsrs.isNew(fresh))
        assertEquals(0.0, fresh.stabilityDays)
        assertEquals(0, fresh.repetitions)
    }

    @Test
    fun theFirstAnswerSetsStabilityAndTheBetterTheAnswerTheLongerItLasts() {
        val byGrade = listOf(Grade.AGAIN, Grade.HARD, Grade.GOOD, Grade.EASY)
            .map { fsrs.schedule(fsrs.initial(), it).stabilityDays }

        assertEquals(byGrade.sorted(), byGrade, "a better first answer must not mean less stability: $byGrade")
        assertTrue(byGrade.first() < byGrade.last())
        assertTrue(byGrade.all { it > 0.0 })
    }

    @Test
    fun theFirstAnswerSetsDifficultyAndAFailureIsHarderThanAnEasyOne() {
        val afterAgain = fsrs.schedule(fsrs.initial(), Grade.AGAIN).difficulty
        val afterEasy = fsrs.schedule(fsrs.initial(), Grade.EASY).difficulty
        assertTrue(afterAgain > afterEasy, "failing should start an item harder: $afterAgain vs $afterEasy")
        listOf(afterAgain, afterEasy).forEach { assertTrue(it in 1.0..10.0, "difficulty out of range: $it") }
    }

    @Test
    fun failingOnFirstSightCountsALapseAndNoRepetition() {
        val failed = fsrs.schedule(fsrs.initial(), Grade.AGAIN)
        assertEquals(1, failed.lapses)
        assertEquals(0, failed.repetitions)
    }

    // ───────── the model behaving like memory ─────────

    @Test
    fun rememberingGrowsStabilityAndForgettingDoesNot() {
        val before = settled()
        val remembered = fsrs.schedule(before, Grade.GOOD, elapsedDays = 20)
        val forgotten = fsrs.schedule(before, Grade.AGAIN, elapsedDays = 20)

        assertTrue(remembered.stabilityDays > before.stabilityDays, "recall should strengthen")
        assertTrue(forgotten.stabilityDays <= before.stabilityDays, "a lapse must never be rewarded")
    }

    @Test
    fun aReviewYouNearlyFailedTeachesMoreThanAnEasyOne() {
        // The headline difference from SM-2, and the reason elapsedDays is on the interface at all.
        // Same item, same grade; the only difference is how long it was left.
        val before = settled()
        val onTime = fsrs.schedule(before, Grade.GOOD, elapsedDays = 5).stabilityDays
        val overdue = fsrs.schedule(before, Grade.GOOD, elapsedDays = 60).stabilityDays

        assertTrue(overdue > onTime, "recalling something nearly lost should be worth more: $overdue vs $onTime")
    }

    @Test
    fun sm2CannotTellTheDifference() {
        // The same contrast against SM-2, so the distinction is pinned rather than asserted in prose.
        val state = ReviewState(repetitions = 3, intervalDays = 20)
        val onTime = Sm2.schedule(state, Grade.GOOD, elapsedDays = 5)
        val overdue = Sm2.schedule(state, Grade.GOOD, elapsedDays = 60)
        assertEquals(onTime, overdue, "SM-2 has no term for lateness and must be unaffected by it")
    }

    @Test
    fun anEasyAnswerBeatsAHardOne() {
        val before = settled()
        val hard = fsrs.schedule(before, Grade.HARD, elapsedDays = 20).stabilityDays
        val good = fsrs.schedule(before, Grade.GOOD, elapsedDays = 20).stabilityDays
        val easy = fsrs.schedule(before, Grade.EASY, elapsedDays = 20).stabilityDays
        assertTrue(hard < good, "hard should grow less than good: $hard vs $good")
        assertTrue(good < easy, "good should grow less than easy: $good vs $easy")
    }

    @Test
    fun difficultyRisesWhenYouStruggleAndFallsWhenYouDoNot() {
        val before = settled(difficulty = 5.0)
        assertTrue(fsrs.schedule(before, Grade.AGAIN, elapsedDays = 20).difficulty > 5.0)
        assertTrue(fsrs.schedule(before, Grade.EASY, elapsedDays = 20).difficulty < 5.0)
    }

    @Test
    fun difficultyNeverLeavesItsRange() {
        // Damping and mean reversion are what stop it running away; this is the test that says so.
        var punished = settled(difficulty = 5.0)
        repeat(60) { punished = fsrs.schedule(punished, Grade.AGAIN, elapsedDays = 1) }
        assertTrue(punished.difficulty in 1.0..10.0, "difficulty escaped: ${punished.difficulty}")

        var spoiled = settled(difficulty = 5.0)
        repeat(60) { spoiled = fsrs.schedule(spoiled, Grade.EASY, elapsedDays = 30) }
        assertTrue(spoiled.difficulty in 1.0..10.0, "difficulty escaped: ${spoiled.difficulty}")
    }

    @Test
    fun aHarderItemGrowsMoreSlowlyThanAnEasierOne() {
        val easyItem = fsrs.schedule(settled(difficulty = 2.0), Grade.GOOD, elapsedDays = 20).stabilityDays
        val hardItem = fsrs.schedule(settled(difficulty = 9.0), Grade.GOOD, elapsedDays = 20).stabilityDays
        assertTrue(easyItem > hardItem, "an easier item should gain more: $easyItem vs $hardItem")
    }

    // ───────── stability means what it says ─────────

    @Test
    fun stabilityIsDaysUntilRecallFallsToNinetyPercent() {
        // The definition, not a coincidence: at the default retention the interval equals stability.
        val state = settled(stability = 37.0)
        assertEquals(37L, fsrs.intervalDays(state))
        // Not abs(): the Kotlin/Wasm backend fails to compile kotlin.math.abs here, and a range
        // check says the same thing without depending on it.
        val recall = fsrs.retrievability(37, 37.0)
        assertTrue(recall > 0.9 - 1e-9 && recall < 0.9 + 1e-9, "recall at t = S should be 0.9, was $recall")
    }

    @Test
    fun retrievabilityFallsWithTimeAndStartsAtOne() {
        assertEquals(1.0, fsrs.retrievability(0, 20.0))
        val curve = listOf(0L, 5L, 20L, 100L, 1000L).map { fsrs.retrievability(it, 20.0) }
        assertEquals(curve.sortedDescending(), curve, "the forgetting curve must fall: $curve")
        assertTrue(curve.last() > 0.0, "it should approach zero, never reach it")
    }

    @Test
    fun askingForMoreRetentionShortensEveryInterval() {
        // The dial SM-2 does not have.
        val state = settled(stability = 50.0)
        val intervals = listOf(0.95, 0.9, 0.85, 0.8)
            .map { Fsrs(FsrsConfig(requestRetention = it)).intervalDays(state) }
        assertEquals(intervals.sorted(), intervals, "higher retention must mean shorter intervals: $intervals")
        assertTrue(intervals.first() < intervals.last())
    }

    @Test
    fun theCeilingHolds() {
        val capped = Fsrs(FsrsConfig(maxIntervalDays = 30))
        assertEquals(30L, capped.intervalDays(settled(stability = 5_000.0)))
    }

    @Test
    fun anIntervalIsNeverLessThanADay() {
        assertEquals(1L, fsrs.intervalDays(settled(stability = 0.01)))
    }

    @Test
    fun aNewItemHasNoIntervalYet() {
        assertEquals(0L, fsrs.intervalDays(fsrs.initial()))
    }

    // ───────── the shared machinery ─────────

    @Test
    fun spreadingWorksHereToo() {
        // The same clumping problem and the same fix, because it belongs to spacing rather than to
        // any one algorithm.
        val spread = Fsrs(FsrsConfig(fuzzFactor = 0.05))
        val days = (1L..50L).map { spread.intervalDays(settled(stability = 40.0), ItemSeed.of(it)) }
        assertTrue(days.toSet().size >= 3, "fifty identical items landed on ${days.toSet()}")
        assertTrue(days.all { it in 38L..42L }, "spread left its stated reach: ${days.toSet()}")
    }

    @Test
    fun itDrivesTheQueueLikeAnyOtherScheduler() {
        // The whole point of the interface: swapping the algorithm changes nothing above it.
        data class Card(val id: String)

        val queue = ReviewQueue(Card::id, Fsrs())
        val cards = listOf(Card("a"), Card("b"))
        var reviews = emptyMap<String, Review<FsrsState>>()

        assertEquals(2, queue.due(cards, reviews, todayEpochDay = 0).size, "new items are due")

        reviews = reviews + ("a" to queue.record(cards[0], null, Grade.GOOD, todayEpochDay = 0))
        assertEquals(listOf(cards[1]), queue.due(cards, reviews, todayEpochDay = 0), "a was just seen")

        val a = reviews.getValue("a")
        assertTrue(a.state.stabilityDays > 0.0)
        assertTrue(queue.nextDueEpochDay(cards, reviews)!! > 0)
    }

    @Test
    fun theQueuePassesLatenessThrough() {
        // Proves elapsedDays is actually wired, not merely present on the interface.
        data class Card(val id: String)

        val queue = ReviewQueue(Card::id, Fsrs())
        val card = Card("a")
        val first = queue.record(card, null, Grade.GOOD, todayEpochDay = 0)

        val onTime = queue.record(card, first, Grade.GOOD, todayEpochDay = 3).state.stabilityDays
        val late = queue.record(card, first, Grade.GOOD, todayEpochDay = 90).state.stabilityDays
        assertNotEquals(onTime, late, "the queue must tell the scheduler how long it really was")
        assertTrue(late > onTime)
    }

    @Test
    fun leechesAreReportedNotActedOn() {
        assertTrue(fsrs.isLeech(FsrsState(stabilityDays = 1.0, difficulty = 9.0, lapses = 8)))
        assertTrue(!fsrs.isLeech(FsrsState(stabilityDays = 1.0, difficulty = 9.0, lapses = 7)))
    }

    @Test
    fun theLeechThresholdIsConfigurableInTheSamePlaceSm2PutsIt() {
        val strict = Fsrs(FsrsConfig(leechThreshold = 3))
        assertTrue(strict.isLeech(FsrsState(stabilityDays = 1.0, difficulty = 9.0, lapses = 3)))
        assertTrue(!strict.isLeech(FsrsState(stabilityDays = 1.0, difficulty = 9.0, lapses = 2)))
        assertEquals(Sm2Config().leechThreshold, FsrsConfig().leechThreshold, "the defaults should agree")
        assertFailsWith<IllegalArgumentException> { FsrsConfig(leechThreshold = 0) }
    }

    // ───────── validation ─────────

    @Test
    fun aWrongNumberOfWeightsIsRejected() {
        assertFailsWith<IllegalArgumentException> { FsrsConfig(weights = listOf(1.0, 2.0)) }
        assertEquals(19, FsrsConfig.DEFAULT_WEIGHTS.size)
    }

    @Test
    fun animpossibleRetentionIsRejected() {
        listOf(0.0, 1.0, -0.5, 1.5).forEach {
            assertFailsWith<IllegalArgumentException>("retention $it should be rejected") {
                FsrsConfig(requestRetention = it)
            }
        }
    }

    @Test
    fun negativeStateIsRejected() {
        assertFailsWith<IllegalArgumentException> { FsrsState(stabilityDays = -1.0) }
        assertFailsWith<IllegalArgumentException> { FsrsState(difficulty = -1.0) }
        assertFailsWith<IllegalArgumentException> { FsrsState(lapses = -1) }
    }
}
