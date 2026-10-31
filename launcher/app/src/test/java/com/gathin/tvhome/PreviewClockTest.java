package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class PreviewClockTest {
    @Test public void interpolatesOnlyUntilTheNextExpectedSample() {
        PreviewClock c = new PreviewClock();
        c.sample(1000, 10000, 2000, true);
        assertEquals(0.105f, c.fraction(2050), 0.00001f);
        assertEquals(0.11f, c.fraction(9000), 0.00001f);
    }
    @Test public void bufferingFreezesWithoutRewindingAndThenResumes() {
        PreviewClock c = new PreviewClock();
        c.sample(1000, 10000, 2000, true);
        float shown = c.fraction(2080);
        c.sample(1000, 10000, 2100, false);
        assertEquals(shown, c.fraction(8000), 0f);
        assertFalse(c.running());
        c.sample(1200, 10000, 8100, true);
        assertEquals(0.125f, c.fraction(8150), 0.00001f);
    }
    @Test public void completionAndResetCannotLeakProgressToTheNextVideo() {
        PreviewClock c = new PreviewClock();
        c.sample(29000, 30000, 1000, true);
        c.finish();
        assertEquals(1f, c.fraction(5000), 0f);
        c.reset();
        assertEquals(0f, c.fraction(6000), 0f);
        assertFalse(c.running());
    }
}
