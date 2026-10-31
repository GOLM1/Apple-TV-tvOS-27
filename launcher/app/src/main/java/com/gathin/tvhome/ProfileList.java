package com.gathin.tvhome;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Small versioned profile catalogue, with stable IDs independent of display names. */
final class ProfileList {
    static final int MAX = 8;
    static final String DEFAULT = "default";

    static final class Profile {
        final String id, name, avatar;
        Profile(String id, String name, String avatar) {
            if (!validId(id)) throw new IllegalArgumentException("Invalid profile ID");
            if (!validName(name)) throw new IllegalArgumentException("Invalid profile name");
            if (!validAvatar(avatar)) throw new IllegalArgumentException("Invalid avatar ID");
            this.id = id; this.name = name.trim(); this.avatar = avatar;
        }
        @Override public boolean equals(Object other) {
            return other instanceof Profile && id.equals(((Profile) other).id);
        }
        @Override public int hashCode() { return id.hashCode(); }
    }

    static boolean validId(String id) {
        return DEFAULT.equals(id) || id != null && id.matches("[a-f0-9]{32}");
    }
    static boolean validName(String name) {
        if (name == null || name.trim().isEmpty() || name.length() > 32) return false;
        for (int i = 0; i < name.length(); i++) if (Character.isISOControl(name.charAt(i))) return false;
        return true;
    }
    static boolean validAvatar(String avatar) {
        return "".equals(avatar) || avatar != null
                && avatar.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    }
    static String encode(List<Profile> profiles) {
        validate(profiles);
        // Android's XML serializer can append indentation after text ending in a newline.
        // Keep the final avatar field intact, including when it is empty, without a final newline.
        StringBuilder text = new StringBuilder("tvhome-profiles 1");
        for (Profile profile : profiles) text.append('\n').append(profile.id).append('\t')
                .append(Base64.getEncoder().encodeToString(profile.name.getBytes(StandardCharsets.UTF_8)))
                .append('\t').append(profile.avatar);
        return text.toString();
    }
    static List<Profile> decode(String text, String defaultName) {
        List<Profile> profiles = new ArrayList<>();
        if (text == null) {
            profiles.add(new Profile(DEFAULT, defaultName, ""));
            return profiles;
        }
        if (text.length() > 8192) throw new IllegalArgumentException("Profile catalogue too large");
        String[] lines = text.split("\n");
        if (lines.length < 2 || !"tvhome-profiles 1".equals(lines[0])) throw new IllegalArgumentException("Invalid catalogue header");
        for (int i = 1; i < lines.length; i++) {
            // Existing preference strings may contain XML formatting after the last real row.
            // Never trim a real row: its final tab represents a valid empty avatar field.
            if (lines[i].trim().isEmpty()) continue;
            String[] parts = lines[i].split("\t", -1);
            if (parts.length != 3) throw new IllegalArgumentException("Invalid profile row " + i + ": " + parts.length + " fields");
            profiles.add(new Profile(parts[0], new String(Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8), parts[2]));
        }
        validate(profiles);
        return profiles;
    }
    private static void validate(List<Profile> profiles) {
        if (profiles.isEmpty() || profiles.size() > MAX) throw new IllegalArgumentException("Invalid profile count");
        Set<String> ids = new HashSet<>();
        for (Profile profile : profiles) if (!ids.add(profile.id)) throw new IllegalArgumentException("Duplicate profile ID");
        if (!ids.contains(DEFAULT)) throw new IllegalArgumentException("Original profile missing");
    }
}
