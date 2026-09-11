package io.github.meko123456.srs

/**
 * How well an item was recalled, on SM-2's 0–5 quality scale.
 *
 * SM-2 defines six qualities; four is what review UIs actually offer, and the four here are the
 * conventional mapping (the same one Anki and SuperMemo's own clients settled on). [quality] is
 * exposed because the ease-factor formula is defined in terms of it, and because a caller with a
 * different scale — a 1–5 star rating, say — can map onto these.
 */
public enum class Grade(public val quality: Int) {
    /** Failed to recall. Resets the streak and counts a lapse. */
    AGAIN(1),

    /** Recalled, but with difficulty. A pass that lowers the ease factor. */
    HARD(3),

    /** Recalled correctly. A pass that leaves the ease factor alone. */
    GOOD(4),

    /** Recalled effortlessly. A pass that raises the ease factor. */
    EASY(5),
    ;

    /**
     * Whether this grade counts as a successful recall.
     *
     * SM-2's threshold is quality >= 3, which is why [HARD] is a pass despite lowering ease.
     */
    public val isPass: Boolean get() = quality >= PASS_THRESHOLD

    public companion object {
        /** The quality at or above which SM-2 treats a review as successful. */
        public const val PASS_THRESHOLD: Int = 3
    }
}
