package io.github.besliky.airplaytv.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsTimingTest {
    @Test fun `advance selects the current lyric without changing the audio timeline`() {
        val lyrics = Lyrics(Lyrics.parseLrc("[00:10] first\n[00:11] second"), synced = true)
        assertEquals(0, lyrics.indexAt(10_000))
        assertEquals(1, lyrics.indexAt(LyricsTiming.position(10_000, 60_000, 1000, true)))
        assertEquals(0, lyrics.indexAt(LyricsTiming.position(11_000, 60_000, -1000, true)))
    }

    @Test fun `plain lyrics and unknown progress are not advanced`() {
        assertEquals(10_000L, LyricsTiming.position(10_000, 60_000, 1000, false))
        assertEquals(-1L, LyricsTiming.position(-1, 60_000, 1000, true))
        assertEquals(-1L, LyricsTiming.position(0, -1, 1000, true))
    }

    @Test fun `calibration stays within the track and supported range`() {
        assertEquals(0L, LyricsTiming.position(500, 60_000, -1000, true))
        assertEquals(60_000L, LyricsTiming.position(59_500, 60_000, 1000, true))
        assertEquals(13_000L, LyricsTiming.position(10_000, 60_000, 9999, true))
        assertEquals(0, LyricsTiming.DEFAULT_ADVANCE_MS)
    }
}
