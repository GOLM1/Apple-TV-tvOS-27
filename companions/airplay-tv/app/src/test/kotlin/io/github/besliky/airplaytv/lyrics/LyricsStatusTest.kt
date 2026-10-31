package io.github.besliky.airplaytv.lyrics

import io.github.besliky.airplaytv.service.NowPlaying.LyricsStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class LyricsStatusTest {
    @Test fun failedLookupIsNotMissingLyrics() {
        assertEquals(LyricsStatus.ERROR, LyricsStatus.from(Result.failure(IOException("offline"))))
        assertEquals(LyricsStatus.NOT_FOUND, LyricsStatus.from(Result.success(null)))
    }

    @Test fun instrumentalAndPlainLyricsAreSuccessfulResults() {
        assertEquals(LyricsStatus.FOUND, LyricsStatus.from(Result.success(Lyrics.INSTRUMENTAL)))
        val plain = Lyrics(Lyrics.parsePlain("A line"), synced = false)
        assertEquals(LyricsStatus.FOUND, LyricsStatus.from(Result.success(plain)))
    }
}
