package io.github.besliky.airplaytv.service

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayedRtpTest {
    @Test
    fun heardFrameMapsIntoItsBlock() {
        val played = PlayedRtp(4)
        played.record(0, 1000)
        played.record(352, 1352)
        assertEquals(1100L, played.rtpAt(100))
        assertEquals(1400L, played.rtpAt(400))
        assertEquals(-1L, played.rtpAt(-5))
    }

    @Test
    fun rtpWrapsAndUnknownBlocksStayUnknown() {
        val played = PlayedRtp(4)
        played.record(0, 0xFFFFFFF0L)
        assertEquals(0x10L, played.rtpAt(0x20))
        played.record(100, -1)
        assertEquals(-1L, played.rtpAt(150))
    }

    @Test
    fun oldBlocksAreForgotten() {
        val played = PlayedRtp(2)
        played.record(0, 0)
        played.record(10, 10)
        played.record(20, 20)
        assertEquals(-1L, played.rtpAt(5))
        assertEquals(25L, played.rtpAt(25))
    }

    @Test
    fun heardPositionWinsOverTheSenderGuess() {
        val rate = 44100L
        // The sender reports 10 s while 11 s is leaving the speaker.
        assertEquals(11_000L, NowPlayingTracker.heardPositionMs(1000 + 11 * rate, 1000, rate, 10_000))
        // The previous track still playing gives a negative position.
        assertEquals(-500L, NowPlayingTracker.heardPositionMs(0xFFFFFFFFL - rate / 2 + 1, 0, rate, 0))
        // Unknown or implausible: keep the sender's.
        assertEquals(10_000L, NowPlayingTracker.heardPositionMs(-1, 1000, rate, 10_000))
        assertEquals(10_000L, NowPlayingTracker.heardPositionMs(1000 + 60 * rate, 1000, rate, 10_000))
    }
}
