package io.github.meko123456.srs

/**
 * Turns an item's identifier into the seed [Scheduler.schedule] spreads intervals with.
 *
 * The seed only has to be **stable for an item and different between items**. It is not a secret,
 * it is not a hash anyone should rely on for anything else, and it never leaves the scheduler.
 *
 * Use this rather than [String.hashCode]: the standard library makes no promise that a string's
 * hash is the same number on the JVM, on Native and in JavaScript, and a seed that changes between
 * platforms would reshuffle a shared deck the first time it was opened somewhere new. The algorithm
 * here is FNV-1a over UTF-16 code units, written out in full, so it gives the same answer
 * everywhere and will keep giving it.
 *
 * ```
 * val state = Sm2(config).schedule(state, Grade.GOOD, ItemSeed.of(card.id))
 * ```
 */
public object ItemSeed {

    private const val OFFSET_BASIS: Long = -3750763034362895579L // 14695981039346656037 unsigned
    private const val PRIME: Long = 1_099_511_628_211L

    /**
     * A seed for a text identifier — a UUID, a slug, a note id.
     *
     * Never returns [Scheduler.NO_SEED], because that value means "do not spread this one" and an
     * ordinary identifier must not accidentally land on it.
     */
    public fun of(id: String): Long {
        var hash = OFFSET_BASIS
        for (ch in id) {
            hash = hash xor ch.code.toLong()
            hash *= PRIME
        }
        return hash.orNoSeed()
    }

    /**
     * A seed for a numeric identifier — typically a database row id.
     *
     * Worth preferring over passing the id straight in, for one reason: row ids usually start at
     * zero or one, and zero is [Scheduler.NO_SEED]. An app that seeded with raw row ids would find
     * its very first item quietly never spread, which is exactly the kind of bug that is invisible
     * until someone counts.
     */
    public fun of(id: Long): Long {
        var z = id * -7046029254386353131L
        z = (z xor (z ushr 32)) * -4658895280553007687L
        z = z xor (z ushr 29)
        return z.orNoSeed()
    }

    private fun Long.orNoSeed(): Long = if (this == Scheduler.NO_SEED) PRIME else this
}
