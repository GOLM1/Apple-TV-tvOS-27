package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ShelfPagingTest {
    @Test
    public void rightAndLeftMoveOneSlide() {
        assertEquals(3, ContinueWatching.pageTarget(2, 1, 8));
        assertEquals(1, ContinueWatching.pageTarget(2, -1, 8));
    }

    @Test
    public void pagingWrapsAtBothEnds() {
        assertEquals(0, ContinueWatching.pageTarget(7, 1, 8));
        assertEquals(7, ContinueWatching.pageTarget(0, -1, 8));
    }
}
