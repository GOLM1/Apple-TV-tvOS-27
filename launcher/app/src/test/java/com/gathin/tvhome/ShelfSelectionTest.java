package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class ShelfSelectionTest {
    @Test public void slowerEarlierArtworkCannotReplaceTheNewerSlide() {
        ShelfSelection<String> selection = new ShelfSelection<>();
        int a = selection.begin();
        int b = selection.begin();
        assertTrue(selection.commit(b, "B title + B artwork"));
        assertFalse(selection.commit(a, "A title + A artwork"));
        assertEquals("B title + B artwork", selection.visible());
    }
    @Test public void pendingRequestKeepsTheCurrentlyDisplayedActionAndArtworkTogether() {
        ShelfSelection<String> selection = new ShelfSelection<>();
        selection.commit(selection.begin(), "A");
        int next = selection.begin();
        assertEquals("A", selection.visible());
        selection.commit(next, "B");
        assertEquals("B", selection.visible());
    }
    @Test public void switchingAppsInvalidatesInflightArtworkAndThePreviousAction() {
        ShelfSelection<String> selection = new ShelfSelection<>();
        int old = selection.begin();
        selection.clear();
        assertFalse(selection.commit(old, "previous app"));
        assertNull(selection.visible());
    }
    @Test public void episodeLabelsDoNotInventMissingNumbers() {
        assertEquals("S1, E10", ContinueWatching.episodeLabel("1", "10"));
        assertEquals("E10", ContinueWatching.episodeLabel(null, "10"));
        assertEquals("", ContinueWatching.episodeLabel(null, null));
        assertEquals("Specials", ContinueWatching.episodeLabel("Specials", ""));
    }
    @Test public void titleLogoUsesTheSameIdentifiedWorkAsTheBackdrop() {
        ContinueWatching.Item item = new ContinueWatching.Item("Glee", "", "com.stremio.one",
                "https://images.metahub.space/background/large/tt1327801/img", "", 0, 0, false);
        assertEquals("https://images.metahub.space/logo/medium/tt1327801/img", ContinueWatching.titleLogoUri(item));
    }
    @Test public void unknownTitlesKeepTextInsteadOfGuessingArtwork() {
        ContinueWatching.Item item = new ContinueWatching.Item("Title", "", "com.example.app",
                "https://example.com/background.jpg", "app://tt1327801", 0, 0, false);
        assertNull(ContinueWatching.titleLogoUri(item));
        ContinueWatching.Item stremio = new ContinueWatching.Item("Title", "", "com.stremio.one",
                null, "stremio:///detail/series/tt1327801/tt1327801:2:7", 0, 0, false);
        assertEquals("https://images.metahub.space/logo/medium/tt1327801/img", ContinueWatching.titleLogoUri(stremio));
    }

}
