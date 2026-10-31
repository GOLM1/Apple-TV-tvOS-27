package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class AirPlayMusicTest {
    @Test public void cardKeepsPausedMusicButClearsStoppedSessions() {
        assertTrue(AirPlayMusic.visible("Song", 3));
        assertTrue(AirPlayMusic.visible("Song", 2));
        assertTrue(AirPlayMusic.visible("Song", 6));
        assertFalse(AirPlayMusic.visible("Song", 1));
        assertFalse(AirPlayMusic.visible("Song", 0));
        assertFalse(AirPlayMusic.visible("Song", 7));
    }
    @Test public void mirrorWithoutTrackCannotReplaceClock() {
        assertFalse(AirPlayMusic.visible(null, 3));
        assertFalse(AirPlayMusic.visible("  ", 3));
    }
}
