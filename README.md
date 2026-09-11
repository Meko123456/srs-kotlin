# srs-kotlin

[![CI](https://github.com/Meko123456/srs-kotlin/actions/workflows/ci.yml/badge.svg)](https://github.com/Meko123456/srs-kotlin/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![API docs](https://img.shields.io/badge/API-docs-blue.svg)](https://meko123456.github.io/srs-kotlin/)

Spaced-repetition scheduling for Kotlin Multiplatform: a configurable, exhaustively tested **SM-2**
implementation with **no dependencies**.

It answers the two questions a review screen actually has — *what should I study now*, and *what do
I write down when the answer comes back* — and stays out of everything else. No database, no clock,
no date library, no UI.

```kotlin
val queue = ReviewQueue(Card::id)

val toStudy = queue.due(cards, reviews, today)                    // what to show
val updated = queue.record(reviews[card.id], Grade.GOOD, today)   // what to store
```

## Why this exists

I had written SM-2 twice — once in [Barati](https://github.com/Meko123456/Barati) (flashcards) and
once in [PrepParrot](https://github.com/Meko123456/PrepParrot) (interview rehearsal) — and the two
copies had drifted into being the same ninety lines with different comments. This is that code,
extracted, made configurable, and tested properly.

Both apps' original test suites are carried over in [`Sm2Test`](srs/src/commonTest/kotlin/io/github/meko123456/srs/Sm2Test.kt)
unchanged in meaning, so the library is a drop-in for a hand-rolled SM-2.

## Install

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.meko123456:srs:0.1.0")
}
```

Targets: **JVM**, **Android** (minSdk 21), **iosArm64**, **iosSimulatorArm64**, **JS**, **WasmJS**.

The test suite runs on four of them in CI — the JVM, an iOS simulator, Node and Wasm — which covers
three different compiler backends.

## How SM-2 works

Each item carries an *ease factor*, starting at 2.5. A successful review multiplies the current
interval by that ease; a failure sends the item back to tomorrow. The ease itself moves with how hard
the recall was, so an item you find easy drifts toward months apart while one you keep fumbling stays
close.

```kotlin
var state = ReviewState()                    // a new item
state = Sm2.schedule(state, Grade.GOOD)      // interval 1 day
state = Sm2.schedule(state, Grade.GOOD)      // interval 6 days
state = Sm2.schedule(state, Grade.GOOD)      // interval 15 days  (6 × 2.5)
state = Sm2.schedule(state, Grade.AGAIN)     // back to 1 day, ease down, one lapse recorded
```

The first two successful intervals are fixed (1 day, then 6) rather than computed — starting from an
interval of zero, the multiplication would never leave zero.

`Sm2` works as a plain function holder for the textbook defaults, so nothing needs constructing in
the common case.

## Time is epoch days

Every date in this library is a **`Long` epoch day** — days since 1970-01-01 — never an instant.

Spaced repetition is a day-grained problem: an item due "today" is due whether it is looked at over
breakfast or at midnight, and an interval of six days means six calendar days. It also keeps the
library free of a date dependency. The conversion is one call:

```kotlin
// kotlinx-datetime
val today = Clock.System.todayIn(TimeZone.currentSystemDefault()).toEpochDays().toLong()

// java.time
val today = LocalDate.now().toEpochDay()
```

Nothing here reads a clock. `today` is always a parameter, which is what makes every behaviour in
this README testable without mocking time.

## Tuning it

`Sm2Config()` is textbook SM-2. Every number the algorithm uses is a field, so tuning your app's pace
never means forking the algorithm:

```kotlin
val sm2 = Sm2(
    Sm2Config(
        firstIntervalDays = 2,      // ease new material in more slowly
        intervalModifier = 0.9,     // review everything ~10% more often
        maxIntervalDays = 180,      // nothing disappears for more than six months
        leechThreshold = 5,         // flag an item after five failures
    ),
)
```

| Setting | Default | What it does |
|---|---|---|
| `initialEase` | 2.5 | Ease a new item starts at |
| `minEase` | 1.3 | Floor, so a repeatedly failed item stays learnable |
| `firstIntervalDays` | 1 | Interval after the first success |
| `secondIntervalDays` | 6 | Interval after the second success |
| `maxIntervalDays` | 36500 | Cap on any interval |
| `intervalModifier` | 1.0 | Global scale on computed intervals |
| `lapseIntervalDays` | 1 | Where a failure sends the item |
| `leechThreshold` | 8 | Lapses before `isLeech` reports true |

## Leeches

An item that keeps being forgotten is usually better rewritten than re-reviewed — SM-2's own advice.
`ReviewState.lapses` counts failures and is never reset, so the history of a difficult item survives a
good run:

```kotlin
if (sm2.isLeech(state)) { /* suspend it, tag it, split it in two */ }
```

This is reporting only. Nothing in the library changes behaviour for a leech; what to do about one is
your call.

## Using another algorithm

`Sm2` implements [`Scheduler`](srs/src/commonMain/kotlin/io/github/meko123456/srs/Scheduler.kt), and
`ReviewQueue` takes any `Scheduler`. Leitner boxes, a fixed ladder or FSRS slot in without the review
screen noticing:

```kotlin
val queue = ReviewQueue(Card::id, scheduler = MyOwnScheduler())
```

Implementations must be pure — same inputs, same outputs, no clock reads.

## Persisting review state

`ReviewState` and `Review` are plain data classes of primitives, with **no `@Serializable`**: a
scheduling library has no business forcing a serialization framework on you. Map them to your own
stored row:

```kotlin
@Serializable
data class StoredReview(
    val repetitions: Int,
    val intervalDays: Long,
    val easeFactor: Double,
    val lapses: Int,
    val lastReviewedEpochDay: Long,
)

fun StoredReview.toReview() = Review(
    ReviewState(repetitions, intervalDays, easeFactor, lapses),
    lastReviewedEpochDay,
)
```

`ReviewState` validates itself on construction, so a corrupted row fails loudly at the boundary
rather than producing silently wrong intervals later.

## API

| Type | What it is |
|---|---|
| `Grade` | `AGAIN` / `HARD` / `GOOD` / `EASY`, mapped onto SM-2's 0–5 quality scale |
| `ReviewState` | One item's history: repetitions, interval, ease, lapses |
| `Review` | A `ReviewState` plus the epoch day it was last seen |
| `Scheduler` | The algorithm interface |
| `Sm2` / `Sm2Config` | SM-2 and its knobs |
| `ReviewQueue` | Due selection, ordering, counts, and recording a result |

Due ordering: never-seen items first, then longest overdue. Ties keep the order you passed in, so
shuffling or deck order is yours to decide by ordering the input.

## A worked example

[`:sample`](sample/src/main/kotlin/Main.kt) runs a six-card deck through thirty days and prints what
comes up each day, grading each card the way a learner roughly would. No clock: `today` is a counter,
which is why the output is identical on every run.

```sh
./gradlew :sample:run
```

```
day  8   3 due
          წყალი — water          EASY  → next in 16 day(s)
          სახლი — house          GOOD  → next in 16 day(s)
          მეგობარი — friend      HARD  → next in  6 day(s)
...
After thirty days — 41 reviews in total:

  card                   interval   lapses    ease
  გამარჯობა — hello            38        0    2.56
  წყალი — water                43        0    2.70
  წიგნი — book                 12        2    1.36
  მეგობარი — friend             1       11    1.30
```

The cards that came easily end up weeks apart; the one that was fought for is still at a day, has
floored its ease, and is reported as a leech.

## API docs

Generated from the KDoc on every push to `main`: **<https://meko123456.github.io/srs-kotlin/>**

```sh
./gradlew :srs:dokkaGeneratePublicationHtml   # build them locally into srs/build/dokka/html
```

## Building

```sh
./gradlew apiCheck                      # the public ABI still matches api/
./gradlew apiDump                       # ...update it when a change is intended
./gradlew :srs:jvmTest                  # the suite on the JVM
./gradlew :srs:iosSimulatorArm64Test    # the same suite on an iOS simulator
./gradlew :srs:jsNodeTest               # and on Node
./gradlew :srs:wasmJsNodeTest           # and on Wasm
./gradlew :srs:assemble                 # every target artifact
```

## License

MIT — see [LICENSE](LICENSE).
