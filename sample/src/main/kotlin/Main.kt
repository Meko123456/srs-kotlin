package io.github.meko123456.srs.sample

import io.github.meko123456.srs.Grade
import io.github.meko123456.srs.Review
import io.github.meko123456.srs.ReviewQueue
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

public fun main() {
    val sm2 = Sm2()
    val queue = ReviewQueue(Card::id, sm2)
    var reviews = emptyMap<String, Review>()

    println("A six-card deck, thirty days, no clock in sight.\n")

    var studied = 0
    for (day in 0L until 30L) {
        val due = queue.due(deck, reviews, day)
        if (due.isEmpty()) {
            val next = queue.nextDueEpochDay(deck, reviews)
            val wait = if (next == null) "nothing scheduled" else "next on day $next"
            println("day %2d   nothing due  (%s)".format(day, wait))
            continue
        }

        println("day %2d   %d due".format(day, due.size))
        // Only so much study happens in one sitting; the rest rolls over, still due tomorrow.
        for (card in due.take(4)) {
            val existing = reviews[card.id]
            val grade = learner.grade(card, existing?.state?.repetitions ?: 0, seed = (day * 31 + card.id.hashCode()).toInt())
            val updated = queue.record(card, existing, grade, day)
            reviews = reviews + (card.id to updated)
            studied++

            val leech = if (sm2.isLeech(updated.state)) "  ← leech" else ""
            println(
                "          %-22s %-5s → next in %2d day(s)%s".format(
                    card.front, grade, updated.state.intervalDays, leech,
                ),
            )
        }
    }

    println("\nAfter thirty days — $studied reviews in total:\n")
    println("  %-22s %8s %8s %7s".format("card", "interval", "lapses", "ease"))
    deck.forEach { card ->
        val state = reviews[card.id]?.state
        if (state == null) {
            println("  %-22s %8s".format(card.front, "unseen"))
        } else {
            println(
                "  %-22s %8d %8d %7.2f".format(card.front, state.intervalDays, state.lapses, state.easeFactor),
            )
        }
    }
    println("\nThe cards that came easily are weeks apart; the ones that were fought for are still close.")
}
