package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class IndicatorStateTest {
    @Test public void confirmingTheSameTargetDoesNotSnapOrRestartItsMorph() {
        IndicatorState s = new IndicatorState();
        s.reset(8);
        s.select(0, 0, false);
        s.select(1, 100, true);
        s.update(180);
        float weight = s.weight(1);
        assertTrue(weight > 0f && weight < 1f);
        s.select(1, 180, true);
        assertEquals(weight, s.weight(1), 0f);
        s.update(420);
        assertEquals(1f, s.weight(1), 0f);
    }

    @Test public void heldPagingTracksEveryTargetAndSettlesOnTheLastOne() {
        IndicatorState s = new IndicatorState();
        s.reset(10);
        s.select(0, 0, false);
        for (int step = 1; step <= 23; step++) {
            long time = step * PagingRepeat.INTERVAL_MS;
            s.update(time);
            float[] before = new float[s.count()];
            for (int i = 0; i < before.length; i++) before[i] = s.weight(i);
            s.select(step % 10, time, true);
            assertEquals(step % 10, s.selected());
            for (int i = 0; i < before.length; i++) assertEquals(before[i], s.weight(i), 0f);
        }
        s.update(23 * PagingRepeat.INTERVAL_MS + IndicatorState.MORPH_MS);
        for (int i = 0; i < s.count(); i++) assertEquals(i == 3 ? 1f : 0f, s.weight(i), 0f);
    }

    @Test public void morphKeepsTheGroupCentredWithConstantExtent() {
        IndicatorState s = new IndicatorState();
        s.reset(8);
        s.select(0, 0, false);
        s.select(1, 100, true);
        for (int t = 100; t <= 500; t += 16) {
            s.update(t);
            float extent = 7 * IndicatorState.GAP;
            for (int i = 0; i < 8; i++) extent += s.width(i);
            assertEquals(s.width(), extent, 0.0001f);
        }
        assertEquals(IndicatorState.PILL, s.width(1), 0f);
    }
    @Test public void rapidPagingStartsAtTheVisibleShape() {
        IndicatorState s = new IndicatorState();
        s.reset(8);
        s.select(0, 0, false);
        s.select(1, 100, true);
        s.update(200);
        float old = s.weight(0), next = s.weight(1);
        s.select(7, 200, true);
        assertEquals(old, s.weight(0), 0f);
        assertEquals(next, s.weight(1), 0f);
        s.update(600);
        assertEquals(1f, s.weight(7), 0f);
        assertEquals(0f, s.weight(0), 0f);
    }
    @Test public void wrappingAndChangingAppDropThePreviousTarget() {
        IndicatorState s = new IndicatorState();
        s.reset(8);
        s.select(7, 0, false);
        s.select(0, 10, true);
        s.update(400);
        assertEquals(1f, s.weight(0), 0f);
        s.reset(2);
        s.select(1, 500, false);
        assertEquals(IndicatorState.PILL, s.width(1), 0f);
        s.reset(0);
        s.select(0, 600, true);
        assertEquals(0f, s.width(), 0f);
    }
}
