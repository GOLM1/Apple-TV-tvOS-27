package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class PagingRepeatTest {
    @Test public void separatePressesAreNeverLostToTheHoldInterval() {
        PagingRepeat repeat = new PagingRepeat();
        assertTrue(repeat.accept(1, 100, false));
        assertTrue(repeat.accept(1, 110, false));
        assertTrue(repeat.accept(1, 120, false));
    }

    @Test public void holdingAdvancesAtMostOncePerIntervalWithoutAccumulatingMoves() {
        PagingRepeat repeat = new PagingRepeat();
        assertTrue(repeat.accept(1, 0, false));
        for (int time = 10; time < PagingRepeat.INTERVAL_MS; time += 10) {
            assertFalse(repeat.accept(1, time, true));
        }
        assertTrue(repeat.accept(1, PagingRepeat.INTERVAL_MS, true));
        assertFalse(repeat.accept(1, PagingRepeat.INTERVAL_MS + 1, true));
        assertTrue(repeat.accept(1, 5000, false));
    }

    @Test public void reversingDirectionDoesNotWaitForTheOldHold() {
        PagingRepeat repeat = new PagingRepeat();
        assertTrue(repeat.accept(1, 0, false));
        assertTrue(repeat.accept(-1, 10, true));
        assertFalse(repeat.accept(-1, 20, true));
    }

    @Test public void focusEnteringThePagingEdgeDuringAHoldAcceptsItsFirstRepeat() {
        PagingRepeat repeat = new PagingRepeat();
        assertTrue(repeat.accept(1, 100, true));
    }
}
