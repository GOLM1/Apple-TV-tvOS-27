package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LiquidGlassTest {
    @Test
    public void missingArtworkStillCompletesTheSlide() {
        int[] completed = {0};
        LiquidGlass.prepare(null, () -> completed[0]++);
        assertEquals(1, completed[0]);
        LiquidGlass.prepare(null); // Preparing no artwork without a callback is also valid.
    }

    @Test public void opaqueVideoLeavesNoPosterInClearGlass() {
        assertEquals(0f, LiquidGlass.layerOpacity(1f, 1f, true), 0f);
        assertEquals(175f / 255f, LiquidGlass.layerOpacity(1f, 0f, true), 0.000001f);
    }

    @Test public void heldFrameCoversBothPosterAndVideoWithoutGhosting() {
        for (boolean clear : new boolean[]{false, true}) {
            assertEquals(0f, LiquidGlass.layerOpacity(1f, 1f, clear), 0f);
            assertEquals(0f, LiquidGlass.layerOpacity(0.5f, 1f, clear), 0f);
        }
    }

    @Test public void fadesMatchOneTranslucentCompositeAtEveryLayer() {
        float[] steps = {0f, 0.1f, 0.5f, 0.9f, 1f};
        for (boolean clear : new boolean[]{false, true}) {
            float material = clear ? 175f / 255f : 1f;
            for (float poster : steps) for (float video : steps) for (float held : steps) {
                float p = LiquidGlass.layerOpacity(poster, held + video * (1f - held), clear);
                float v = LiquidGlass.layerOpacity(video, held, clear);
                float h = LiquidGlass.layerOpacity(held, 0f, clear);
                // Coefficients of three distinct source colours after Canvas source-over blending.
                assertEquals(material * poster * (1f - video) * (1f - held), p * (1f - v) * (1f - h), 0.000001f);
                assertEquals(material * video * (1f - held), v * (1f - h), 0.000001f);
                assertEquals(material * held, h, 0.000001f);
            }
        }
    }

    @Test public void sourceAndCoverageOpacityStayInRange() {
        assertEquals(0f, LiquidGlass.layerOpacity(-1f, 0f, true), 0f);
        assertEquals(0f, LiquidGlass.layerOpacity(1f, 2f, true), 0f);
        assertEquals(175f / 255f, LiquidGlass.layerOpacity(2f, -1f, true), 0.000001f);
    }

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
