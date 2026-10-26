package com.gathin.tvhome;
import org.junit.Test;
import static org.junit.Assert.*;
public class TrailerSourceTest {
    @Test public void requiresExactPublicIdentity() {
        assertNull(TrailerSource.page("", true, null));
        assertNull(TrailerSource.page("", true, "https://example.com/umc.cmc.untrusted"));
        assertEquals("https://tv.apple.com/us/show/_/umc.cmc.series123",
                TrailerSource.page("episode:umc.cmc.episode456", false,
                        "https://tv.apple.com/show/umc.cmc.series123"));
        assertEquals("https://tv.apple.com/us/movie/_/umc.cmc.movie123",
                TrailerSource.page("", true, "https://tv.apple.com/movie/umc.cmc.movie123"));
        assertEquals("https://tv.apple.com/us/movie/_/umc.cmc.movie456",
                TrailerSource.page("https://tv.apple.com/movie/umc.cmc.movie456", true, null));
    }
    @Test public void rejectsFullFeaturesAndLongExtras() {
        assertNull(TrailerSource.previewMetrics("\"mediaMetrics\":{\"duration\":145000,\"contentType\":\"Movie\"}"));
        assertNull(TrailerSource.previewMetrics("\"mediaMetrics\":{\"duration\":600000,\"contentType\":\"Extra\"}"));
        assertNotNull(TrailerSource.previewMetrics("\"mediaMetrics\":{\"duration\":145000,\"contentType\":\"Extra\"}"));
    }
    @Test public void selectsOnlyCompatibleBoundedPublicPreviewVariant() {
        String low = "https://vod-ak-aoc.tv.apple.com/itunes-assets/VideoPreview211/low.m3u8";
        String hd = "https://vod-ak-aoc.tv.apple.com/itunes-assets/VideoPreview211/hd.m3u8";
        String master = "#EXT-X-STREAM-INF:CODECS=\"avc1\",VIDEO-RANGE=SDR,RESOLUTION=640x360\n"+low
                +"\n#EXT-X-STREAM-INF:CODECS=\"avc1\",VIDEO-RANGE=SDR,RESOLUTION=1280x720\n"+hd
                +"\n#EXT-X-STREAM-INF:CODECS=\"avc1\",VIDEO-RANGE=SDR,RESOLUTION=3840x2160\nhttps://example.com/full.m3u8";
        assertEquals(hd, TrailerSource.variant(master));
    }
    @Test public void selectsMatchingPublicPreviewAudioVariant() {
        String low = "https://vod-ak-aoc.tv.apple.com/itunes-assets/VideoPreview211/low.m3u8";
        String hd = "https://vod-ak-aoc.tv.apple.com/itunes-assets/VideoPreview211/hd.m3u8";
        String audio = "https://vod-ak-aoc.tv.apple.com/itunes-assets/VideoPreview211/audio.m3u8";
        String master = "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio-hd\",DEFAULT=YES,URI=\""+audio+"\"\n"
                +"#EXT-X-STREAM-INF:CODECS=\"avc1\",VIDEO-RANGE=SDR,RESOLUTION=640x360,AUDIO=\"audio-low\"\n"+low
                +"\n#EXT-X-STREAM-INF:CODECS=\"avc1\",VIDEO-RANGE=SDR,RESOLUTION=1280x720,AUDIO=\"audio-hd\"\n"+hd;
        assertEquals(audio, TrailerSource.audioVariant(master));
    }
}
