package io.github.meko123456.srs.sample

import io.github.meko123456.srs.Fsrs
import io.github.meko123456.srs.FsrsState
import io.github.meko123456.srs.Grade
import io.github.meko123456.srs.Review
import io.github.meko123456.srs.Scheduler
import io.github.meko123456.srs.ReviewQueue
import io.github.meko123456.srs.ReviewState
import io.github.meko123456.srs.Sm2

/**
 * Thirty days of study, printed.
 *
 * The README shows fragments; this is the whole loop in one file. It runs a small deck through a
 * month, grading each item the way a real learner roughly would — some material sticks immediately,
 * some has to be fought for — and prints what comes up each day and what the intervals grow to.
 *
 * There is no clock anywhere. `today` is a counter, which is exactly how the library is meant to be
 * driven and why this output is identical on every run.
 *
 * It runs the deck twice, once under SM-2 and once under FSRS, against the same learner answering
 * the same way — so the two columns at the end differ only because the algorithms do.
 *
 * `./gradlew :sample:run`
 */

private data class Card(val id: String, val front: String)

/** How reliably a learner recalls each card, so the simulation is varied but deterministic. */
private data class Learner(val knows: Map<String, Double>) {
    fun grade(card: Card, repetitions: Int, seed: Int): Grade {
        val confidence = (knows.getValue(card.id) + repetitions * 0.12).coerceAtMost(0.98)
        // A deterministic stand-in for "did they remember it this time".
        val roll = ((seed * 2654435761u.toInt()) and 0x7fffffff) % 100 / 100.0
        return when {
            roll > confidence -> Grade.AGAIN
            roll > confidence - 0.15 -> Grade.HARD
            roll > confidence - 0.45 -> Grade.GOOD
            else -> Grade.EASY
        }
    }
}

private val deck = listOf(
    Card("ka-1", "გამარჯობა — hello"),
    Card("ka-2", "მადლობა — thank you"),
    Card("ka-3", "წყალი — water"),
    Card("ka-4", "სახლი — house"),
    Card("ka-5", "წიგნი — book"),
    Card("ka-6", "მეგობარი — friend"),
)

private val learner = Learner(
    mapOf(
        "ka-1" to 0.90, // easy, sticks at once
        "ka-2" to 0.80,
        "ka-3" to 0.70,
        "ka-4" to 0.55,
        "ka-5" to 0.40,
        "ka-6" to 0.25, // the one that will keep coming back
    ),
)

/** One algorithm's run: what it ended up scheduling, and how much studying it asked for. */
private data class Run(val name: String, val reviews: Int, val intervals: Map<String, Long>)

private fun <S> study(name: String, scheduler: Scheduler<S>, intervalOf: (S, Long) -> Long): Run {
    val queue = ReviewQueue(Card::id, scheduler)
    var reviews = emptyMap<String, Review<S>>()
    var studied = 0

    for (day in 0L until 30L) {
        // Only so much study happens in one sitting; the rest rolls over, still due tomorrow.
        for (card in queue.due(deck, reviews, day).take(4)) {
            val existing = reviews[card.id]
            val seen = existing?.let { day - it.lastReviewedEpochDay }?.toInt() ?: 0
            val grade = learner.grade(card, seen, seed = (day * 31 + card.id.hashCode()).toInt())
            reviews = reviews + (card.id to queue.record(card, existing, grade, day))
            studied++
        }
    }

    val intervals = deck.associate { card ->
        val review = reviews[card.id]
        card.id to (review?.let { intervalOf(it.state, it.lastReviewedEpochDay) } ?: 0L)
    }
    return Run(name, studied, intervals)
}

public fun main() {
    println("A six-card deck, thirty days, no clock in sight.")
    println("The same learner answers the same way; only the algorithm changes.\n")

    val fsrsScheduler = Fsrs()
    val sm2 = study("SM-2", Sm2()) { state: ReviewState, _ -> state.intervalDays }
    val fsrs = study("FSRS", fsrsScheduler) { state: FsrsState, _ -> fsrsScheduler.intervalDays(state) }

    println("  %-24s %10s %10s".format("card", sm2.name, fsrs.name))
    deck.forEach { card ->
        println(
            "  %-24s %8d d %8d d".format(
                card.front, sm2.intervals.getValue(card.id), fsrs.intervals.getValue(card.id),
            ),
        )
    }
    println("\n  %-24s %8d   %8d".format("reviews in the month", sm2.reviews, fsrs.reviews))

    println(
        """

        Both push the easy cards weeks out and keep the fought-for ones close, which is the whole
        job. They disagree on how far and how fast, and that is the point of being able to choose:
        FSRS derives its intervals from a target retention you set, SM-2 from an ease factor that
        drifts. Neither number here is a verdict — thirty simulated days with one invented learner
        proves nothing about which is better for real material.
        """.trimIndent(),
    )
}
