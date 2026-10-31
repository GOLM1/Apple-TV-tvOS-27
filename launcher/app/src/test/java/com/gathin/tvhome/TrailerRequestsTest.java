package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class TrailerRequestsTest {
    @Test public void replacingPlaybackKeepsTheMatchingPrewarm() {
        TrailerRequests requests = new TrailerRequests();
        int playback = requests.current();
        int prewarm = requests.prewarm("next");
        requests.stop();
        requests.show("next");
        assertTrue(requests.stale(playback));
        assertFalse(requests.stale(prewarm));
    }

    @Test public void showingAnotherSlideCancelsThePrewarm() {
        TrailerRequests requests = new TrailerRequests();
        int prewarm = requests.prewarm("next");
        requests.show("different");
        assertTrue(requests.stale(prewarm));
    }

    @Test public void leavingHomeCancelsRunningPlaybackAndPrewarm() {
        TrailerRequests requests = new TrailerRequests();
        int playback = requests.current();
        int prewarm = requests.prewarm("next");
        requests.cancelPrewarm();
        requests.stop();
        assertTrue(requests.stale(playback));
        assertTrue(requests.stale(prewarm));
        requests.show("next");
        assertTrue(requests.stale(prewarm));
    }

    @Test public void returningHomeAllowsFreshRequests() {
        TrailerRequests requests = new TrailerRequests();
        int old = requests.prewarm("next");
        requests.cancelPrewarm();
        requests.stop();
        requests.show("current");
        int playback = requests.current();
        int fresh = requests.prewarm("next");
        assertTrue(requests.stale(old));
        assertFalse(requests.stale(playback));
        assertFalse(requests.stale(fresh));
    }

    @Test public void slowDownloadSurvivesTwoMinutesOfContinuingProgress() {
        TrailerRequests requests = new TrailerRequests();
        int request = requests.current();
        for (long now = 0; now <= 120_000; now += 5000) {
            requests.progress(request, now);
            assertFalse(requests.stalled(now + 8000));
        }
        assertFalse(requests.stalled(149_999));
        assertTrue(requests.stalled(150_000));
    }

    @Test public void cancelledDownloadCannotExtendTheNewSlidesDeadline() {
        TrailerRequests requests = new TrailerRequests();
        int old = requests.current();
        requests.stop();
        requests.progress(requests.current(), 1000);
        requests.progress(old, 29_000);
        assertTrue(requests.stalled(31_000));
    }

    @Test public void matchingPrewarmKeepsTheVisibleSlideAliveWhileItFinishes() {
        TrailerRequests requests = new TrailerRequests();
        int prewarm = requests.prewarm("next");
        requests.show("next");
        requests.stop();
        requests.progress(requests.current(), 1000);
        requests.progress(prewarm, 25_000);
        assertFalse(requests.stalled(40_000));
        assertTrue(requests.stalled(55_000));
    }

    @Test public void prewarmIdentityAllowsRepeatedPrefetchCallsToBeSkipped() {
        TrailerRequests requests = new TrailerRequests();
        assertFalse(requests.prewarming("next"));
        requests.prewarm("next");
        assertTrue(requests.prewarming("next"));
        assertFalse(requests.prewarming("other"));
        requests.show("next");
        assertFalse(requests.prewarming("next"));
    }
}
