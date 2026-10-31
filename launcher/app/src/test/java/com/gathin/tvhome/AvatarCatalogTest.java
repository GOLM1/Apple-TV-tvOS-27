package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class AvatarCatalogTest {
    private static final String ID = "14372430-36c7-11e8-b308-0e1f42e0af2a";
    private static final String ROW = ID + "\tClassics\tRobot\thttps://occ-0-1.1.nflxso.net/image.png?r=1\n";

    @Test public void catalogueContainsOnlyPublicImageReferences() {
        AvatarCatalog.Entry entry = AvatarCatalog.parse(ROW).get(0);
        assertEquals("Robot", entry.name);
        assertEquals("Classics", entry.group);
        assertEquals(ID, entry.id);
    }
    @Test public void unrelatedHostsLocalFilesAndCredentialsAreRejected() {
        assertFalse(AvatarCatalog.validUrl("file:///sdcard/profile.png"));
        assertFalse(AvatarCatalog.validUrl("https://occ-0.nflxso.net.evil.example/image.png"));
        assertFalse(AvatarCatalog.validUrl("http://occ-0.nflxso.net/image.png"));
        assertFalse(AvatarCatalog.validUrl("https://password@occ-0.nflxso.net/image.png"));
    }
    @Test(expected = IllegalArgumentException.class) public void duplicateImagesCannotCreateAmbiguousSelections() {
        AvatarCatalog.parse(ROW + ROW);
    }
    @Test(expected = IllegalArgumentException.class) public void missingImageFieldsAreRejected() {
        AvatarCatalog.parse(ID + "\tClassics\tRobot");
    }
}
