package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import java.time.LocalDate;

import org.junit.Test;

public class TitleBadgeTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);

    @Test
    public void aRecentSeasonOpenerIsANewSeason() {
        assertEquals("New Season", TitleMetadata.badge("tv", "2026-10-10", 1, "", TODAY));
    }

    @Test
    public void aRecentLaterEpisodeIsANewEpisode() {
        assertEquals("New Episode", TitleMetadata.badge("tv", "2026-10-26", 7, "", TODAY));
    }

    @Test
    public void oldOrMissingDatesGiveNoLabel() {
        assertEquals("", TitleMetadata.badge("tv", "2026-08-01", 1, "", TODAY));
        assertEquals("", TitleMetadata.badge("tv", "", 1, "", TODAY));
        assertEquals("", TitleMetadata.badge("tv", "not a date", 1, "", TODAY));
        assertEquals("", TitleMetadata.badge("tv", "2026-11-05", 1, "", TODAY));   // announced, not aired yet
    }

    @Test
    public void aRecentFilmIsNew() {
        assertEquals("New", TitleMetadata.badge("movie", "", 0, "2026-09-15", TODAY));
        assertEquals("", TitleMetadata.badge("movie", "", 0, "2025-01-01", TODAY));
    }
}
