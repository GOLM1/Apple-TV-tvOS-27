package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LiquidGlassTest {
    private static int stop(int i) {
        return LiquidGlass.SCRIM[i] >>> 24;
    }

    @Test
    public void scrimAlphaFollowsTheStops() {
        int last = LiquidGlass.SCRIM.length - 1;
        assertEquals(stop(0), LiquidGlass.scrimAlpha(0f));
        assertEquals(stop(last / 2), LiquidGlass.scrimAlpha(0.5f));
        assertEquals(stop(last), LiquidGlass.scrimAlpha(1f));
        // Halfway between two stops.
        float mid = (0.5f + 1f / last + 0.5f) / 2f;
        assertEquals(Math.round((stop(last / 2) + stop(last / 2 + 1)) / 2f), LiquidGlass.scrimAlpha(mid));
    }

    @Test
    public void scrimAlphaClampsOffScreen() {
        assertEquals(stop(0), LiquidGlass.scrimAlpha(-0.2f));
        assertEquals(stop(LiquidGlass.SCRIM.length - 1), LiquidGlass.scrimAlpha(1.4f));
    }
}
