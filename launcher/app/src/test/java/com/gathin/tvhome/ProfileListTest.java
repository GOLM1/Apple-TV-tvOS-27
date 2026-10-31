package com.gathin.tvhome;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class ProfileListTest {
    private static final String ID = "0123456789abcdef0123456789abcdef";
    private static final String AVATAR = "14372430-36c7-11e8-b308-0e1f42e0af2a";

    @Test public void firstLaunchPreservesAnOriginalProfileWithoutARealPhoto() {
        List<ProfileList.Profile> profiles = ProfileList.decode(null, "الرئيسي");
        assertEquals(1, profiles.size());
        assertEquals(ProfileList.DEFAULT, profiles.get(0).id);
        assertEquals("", profiles.get(0).avatar);
        assertEquals(Config.SETTINGS_NAME, Profiles.settingsName(profiles.get(0).id));
    }
    @Test public void unicodeNamesAndPictureChoicesRoundTripWithoutChangingIdentity() {
        List<ProfileList.Profile> profiles = Arrays.asList(
                new ProfileList.Profile(ProfileList.DEFAULT, "الرئيسي", ""),
                new ProfileList.Profile(ID, "أنمي 日本 🎬", AVATAR));
        List<ProfileList.Profile> decoded = ProfileList.decode(ProfileList.encode(profiles), "Home");
        assertEquals(ID, decoded.get(1).id);
        assertEquals("أنمي 日本 🎬", decoded.get(1).name);
        assertEquals(AVATAR, decoded.get(1).avatar);
        assertNotEquals(Profiles.settingsName(ProfileList.DEFAULT), Profiles.settingsName(ID));
    }
    @Test public void androidXmlIndentationAfterALegacyFinalNewlineDoesNotHideSavedProfiles() {
        List<ProfileList.Profile> original = Arrays.asList(
                new ProfileList.Profile(ProfileList.DEFAULT, "Home", AVATAR),
                new ProfileList.Profile(ID, "أنمي", ""));
        String stored = ProfileList.encode(original) + "\n    ";
        List<ProfileList.Profile> restored = ProfileList.decode(stored, "Home");
        assertEquals(2, restored.size());
        assertEquals(AVATAR, restored.get(0).avatar);
        assertEquals(ID, restored.get(1).id);
        assertEquals("أنمي", restored.get(1).name);
        assertEquals("", restored.get(1).avatar);
    }
    @Test public void newlySavedCataloguesDoNotTriggerIndentationAndKeepTheEmptyAvatarField() {
        String stored = ProfileList.encode(Arrays.asList(new ProfileList.Profile(ProfileList.DEFAULT, "Home", "")));
        assertFalse(stored.endsWith("\n"));
        assertTrue(stored.endsWith("\t"));
        assertEquals("", ProfileList.decode(stored, "Home").get(0).avatar);
    }
    @Test(expected = IllegalArgumentException.class) public void nonWhitespaceDamageIsStillReportedRatherThanDiscarded() {
        String stored = ProfileList.encode(Arrays.asList(new ProfileList.Profile(ProfileList.DEFAULT, "Home", AVATAR)));
        ProfileList.decode(stored + "\nbroken", "Home");
    }
    @Test public void renameDoesNotChangeTheProfileStorageNamespace() {
        ProfileList.Profile original = new ProfileList.Profile(ID, "First", AVATAR);
        ProfileList.Profile renamed = new ProfileList.Profile(original.id, "Second", "");
        assertEquals(Profiles.settingsName(original.id), Profiles.settingsName(renamed.id));
    }
    @Test public void editingANewProfileStillFindsItAfterTheScreenReloadsTheCatalogue() {
        ProfileList.Profile created = new ProfileList.Profile(ID, "New", "");
        List<ProfileList.Profile> reloaded = ProfileList.decode(ProfileList.encode(Arrays.asList(
                new ProfileList.Profile(ProfileList.DEFAULT, "Home", ""), created)), "Home");
        assertEquals(1, reloaded.indexOf(created));
        assertTrue(reloaded.remove(created));
        assertEquals(ProfileList.DEFAULT, reloaded.get(0).id);
        assertEquals(1, reloaded.size());
    }
    @Test public void unsafeIdentifiersAndUnboundedNamesCannotBecomeStoragePaths() {
        assertFalse(ProfileList.validId("../default"));
        assertFalse(ProfileList.validAvatar("../../profile.png"));
        assertFalse(ProfileList.validName("   "));
        assertFalse(ProfileList.validName("a\nb"));
        assertFalse(ProfileList.validName(String.join("", java.util.Collections.nCopies(33, "a"))));
        assertTrue(ProfileList.validAvatar(AVATAR));
    }
    @Test(expected = IllegalArgumentException.class) public void duplicateIdsCannotMergeTwoProfiles() {
        ProfileList.encode(Arrays.asList(new ProfileList.Profile(ProfileList.DEFAULT, "One", ""),
                new ProfileList.Profile(ProfileList.DEFAULT, "Two", "")));
    }
    @Test(expected = IllegalArgumentException.class) public void theOriginalProfileCannotBeRemovedFromTheCatalogue() {
        ProfileList.encode(Arrays.asList(new ProfileList.Profile(ID, "Other", AVATAR)));
    }
    @Test(expected = IllegalArgumentException.class) public void profilesAreBounded() {
        List<ProfileList.Profile> profiles = new ArrayList<>();
        profiles.add(new ProfileList.Profile(ProfileList.DEFAULT, "Home", ""));
        for (int i = 0; i < ProfileList.MAX; i++) profiles.add(new ProfileList.Profile(
                String.format(java.util.Locale.ROOT, "%032x", i), "Name", ""));
        ProfileList.encode(profiles);
    }
    @Test(expected = IllegalArgumentException.class) public void unreadableSavedProfilesAreNotSilentlyReset() {
        ProfileList.decode("tvhome-profiles 2\nbroken", "Home");
    }
}
