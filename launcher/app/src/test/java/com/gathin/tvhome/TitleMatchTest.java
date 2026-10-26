package com.gathin.tvhome;
import org.junit.Test;
import static org.junit.Assert.*;
public class TitleMatchTest {
    @Test public void matchesTypographyAndAccents() {
        assertTrue(TitleMatch.same("Where’s Wanda?", "Where's Wanda?", ""));
        assertTrue(TitleMatch.same("Yali Capkini", "Yalı Çapkını", "Yali Capkini"));
    }
    @Test public void rejectsSimilarAndEmptyTitles() {
        assertFalse(TitleMatch.same("Glee", "Glee: The Concert", ""));
        assertFalse(TitleMatch.same("", "", ""));
    }
    @Test public void localizedNameAloneCannotIdentifyUnrelatedWorks() {
        assertTrue(TitleMatch.arabic("فريد"));
        assertFalse(TitleMatch.corroborated("قصة شاب غني يتزوج فتاة من عائلة أخرى", "مغامرات سلحفاة صغيرة وأصدقائها"));
        assertTrue(TitleMatch.corroborated("شاب يتزوج فتاة من عائلة أخرى", "فتاة تتزوج شاب ينتمي إلى عائلة أخرى"));
    }
}
