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

> **Not on Maven Central yet.** The release pipeline is wired but has never completed a run — `0.1.0`
> is still blocked on the Maven Central signing key
> ([#1](https://github.com/Meko123456/srs-kotlin/issues/1)) — so the coordinate above does not
> resolve today. Until it does, build from source: clone the repo and either add
> `includeBuild("../srs-kotlin")` to your `settings.gradle.kts`, or run
> `./gradlew publishToMavenLocal` and add `mavenLocal()` to your repositories.

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
| `fuzzFactor` | 0.0 | How far intervals may be spread, so batches stop clumping |

## Stopping batches from clumping

Study fifty new cards in one sitting and SM-2 schedules all fifty for the same day, then the same day
after that, for ever. Every card in the batch follows identical arithmetic, so the clump never
disperses on its own. Anyone who adds material a chapter or a deck at a time builds these by accident.

Turn on `fuzzFactor` and pass a per-item seed:

```kotlin
val sm2 = Sm2(Sm2Config(fuzzFactor = 0.05))

state = sm2.schedule(state, Grade.GOOD, ItemSeed.of(card.id))
```

The spread is **deterministic**, never random: it is derived from the seed, so the same item gets the
same answer on every run and on every device. Two cards that started together drift apart and stay
apart, and the library keeps its promise that the same inputs give the same outputs.

`ItemSeed.of` takes a `String` or a `Long`. Prefer it to passing a row id straight in — ids usually
start at zero, and zero is `Scheduler.NO_SEED`, which means "do not spread this one".

Three details worth knowing:

- **A percentage alone would do nothing to short intervals** — five percent of six days rounds back
  to six days — so the spread reaches at least one day whenever it applies. At 15 days and 5% that
  is 14–16; at 100 days it is 95–105.
- **Intervals under two days are never moved.** An item due tomorrow has nowhere to go that is not
  today, and today is where the failures live. An interval that has been earned is never pushed back
  down to one day either.
- **Leave the seed out and nothing is spread.** So does passing `Scheduler.NO_SEED`. Turning fuzz on
  and forgetting the seed gives you textbook behaviour rather than shifting every card by an
  identical amount, which would look like it was working and disperse nothing.

`fuzzFactor` is `0.0` by default, so `Sm2Config()` is still textbook SM-2 exactly.

## Learning steps

Textbook SM-2 sends a brand-new item straight to a one-day interval: you see something for the first
time, then not again until tomorrow, with nothing in between to say whether it stuck. Anki runs new
material through short steps first — a minute, then ten — and only hands it to the algorithm once it
has survived them. That is a large part of why it feels better on new material.

`LearningQueue` is that phase, and it sits **in front of** the scheduler rather than inside it:

```kotlin
val learning = LearningQueue.Default          // one minute, then ten

when (val outcome = learning.review(item.learning, grade)) {
    is LearningOutcome.StillLearning -> showAgainIn(outcome.inMinutes)
    LearningOutcome.Graduated        -> item.review = Sm2.schedule(ReviewState(), grade)
}
```

Separate types on purpose. Graduating is a different problem from spacing: it happens over minutes,
it asks whether something stuck at all, and it is over within the session. Spacing happens over days
and keeps what has already stuck. Merging them would have meant widening the scheduler's time unit
from epoch days to something finer, and every caller would have paid for a phase that ends within
the hour.

| Grade | What it does |
|---|---|
| `AGAIN` | Back to the first step — not back one, so a failure cannot be undone by one right answer |
| `HARD` | Repeats the current step; barely remembering is not evidence |
| `GOOD` | Advances one step, graduating if that was the last |
| `EASY` | Graduates immediately, unless you turn that off |

A lapsed item gets its own shorter ladder via `enterRelearning()`, because relearning something you
once knew is not the same problem as meeting it for the first time.

`LearningConfig.None` configures no steps at all, which is exactly textbook SM-2 — so an app can
adopt the type without changing how it behaves, and turn the phase on later.

Time is in **minutes** here and days in the scheduler, and neither reads a clock: `dueEpochMinute`
and `isDue` take the minute the item was last seen and the minute it is now.

## Capping the day

Come back after a week away to four hundred due cards and the honest move is to close the app. Daily
limits are what keep a backlog survivable:

```kotlin
val session = queue.session(
    cards, reviews, today,
    limits = DailyLimits(maxReviews = 100, maxNewItems = 20)
        .remainingAfter(reviewsDoneToday, newDoneToday),
)

session.items                                   // what to study now, ordered and capped
"${session.items.size} of ${session.available}" // "20 of 143"
session.isLimited                               // whether anything was held back
```

Reviews and new items have **separate budgets**, because they fail differently: skipping a review
means forgetting something already learned, while skipping a new item means learning it tomorrow
instead. A wall of due reviews therefore never stops new material appearing, and a large import never
pushes out the reviews that are the reason the app works. Within the review budget the **most
overdue survive** — dropping those would mean dropping exactly the items closest to being forgotten.

`remainingAfter` exists because the library cannot know what you studied before it was asked, and
because the two easy ways to get that subtraction wrong are both handled: `UNLIMITED` stays unlimited
rather than becoming a large limit that shrinks all day, and the result never goes negative.

`due()` is simply `session()` with no limits.

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
| `ItemSeed` | Turns an item id into the stable seed interval spreading uses |
| `LearningQueue` / `LearningConfig` | The sub-day steps a new item walks before the scheduler takes over |
| `LearningState` / `LearningOutcome` | Where an item is in those steps, and what a review did to it |
| `ReviewQueue` | Due selection, ordering, counts, and recording a result |
| `DailyLimits` | Per-day caps on reviews and new items, with the headroom arithmetic |
| `StudySession` | What to study now, plus what was available before the cap |

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
