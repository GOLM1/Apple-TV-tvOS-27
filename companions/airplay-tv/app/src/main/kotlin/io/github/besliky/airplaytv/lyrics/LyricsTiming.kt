package io.github.besliky.airplaytv.lyrics

/** Display-only calibration for sender and lyric timestamp differences. */
object LyricsTiming {
    const val DEFAULT_ADVANCE_MS = 0
    const val MIN_ADVANCE_MS = -3000
    const val MAX_ADVANCE_MS = 3000
    val choices = (MIN_ADVANCE_MS..MAX_ADVANCE_MS step 250).toList()

    fun position(positionMs: Long, durationMs: Long, advanceMs: Int, synced: Boolean): Long {
        if (positionMs < 0 || durationMs <= 0) return -1
        val advance = if (synced) advanceMs.coerceIn(MIN_ADVANCE_MS, MAX_ADVANCE_MS) else 0
        return (positionMs + advance).coerceIn(0, durationMs)
    }
}
