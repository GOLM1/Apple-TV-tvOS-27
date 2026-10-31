package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ContinueWatchingTest {
    @Test
    public void cardSizedQueryArtworkIsAskedForAtShelfSize() {
        assertEquals("https://shahid.mbc.net/mediaObject/x?height=720&width=1280&croppingPoint=tc",
                ContinueWatching.sharper("https://shahid.mbc.net/mediaObject/x?height=225&width=400&croppingPoint=tc"));
    }

    @Test
    public void metahubBackgroundsMoveToTheLargeSize() {
        assertEquals("https://images.metahub.space/background/large/tt1327801/img",
                ContinueWatching.sharper("https://images.metahub.space/background/medium/tt1327801/img"));
    }

    @Test
    public void bigOrLocalArtworkIsLeftAlone() {
        String apple = "https://is1-ssl.mzstatic.com/image/thumb/abc/3840x2160.jpg";
        assertEquals(apple, ContinueWatching.sharper(apple));
        assertEquals("https://x.example/a.jpg?width=1920&height=1080",
                ContinueWatching.sharper("https://x.example/a.jpg?width=1920&height=1080"));
        assertEquals("content://org.jellyfin/1", ContinueWatching.sharper("content://org.jellyfin/1"));
        assertEquals(null, ContinueWatching.sharper(null));
    }

    @Test public void titleLogosCoverTheDisplayWidthWithoutChangingOtherImageSources() {
        assertEquals("https://image.tmdb.org/t/p/w780/title.png",
                ContinueWatching.sharperLogo("https://image.tmdb.org/t/p/w500/title.png"));
        String wide = "https://image.tmdb.org/t/p/w1280/title.png";
        assertEquals(wide, ContinueWatching.sharperLogo(wide));
        String local = "content://example/title.png";
        assertEquals(local, ContinueWatching.sharperLogo(local));
        String other = "https://example.com/t/p/w500/title.png";
        assertEquals(other, ContinueWatching.sharperLogo(other));
        assertEquals(null, ContinueWatching.sharperLogo(null));
    }
}
