package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.util.List;

/** Profile identity is separate from theme choices, cache and streaming-app accounts. */
final class Profiles {
    private static boolean loggedRead;
    private static SharedPreferences store(Context context) {
        return context.getSharedPreferences("user_profiles", Context.MODE_PRIVATE);
    }
    static List<ProfileList.Profile> list(Context context) {
        String text = store(context).getString("list", null);
        try {
            List<ProfileList.Profile> result = ProfileList.decode(text, context.getString(R.string.profile_default));
            if (!loggedRead) {
                int formatting = 0;
                if (text != null) for (String row : text.split("\n")) if (row.trim().isEmpty()) formatting++;
                android.util.Log.i("TvHome.Profiles", "Catalogue read: profiles=" + result.size() + ", formatting rows=" + formatting);
                loggedRead = true;
            }
            return result;
        }
        catch (RuntimeException invalid) {
            // Log shape and validation failure only; never profile names, image contents or saved identifiers.
            android.util.Log.e("TvHome.Profiles", "Cannot read catalogue; chars=" + (text == null ? 0 : text.length()), invalid);
            throw invalid;
        }
    }
    static String activeId(Context context) {
        String id = store(context).getString("active", ProfileList.DEFAULT);
        if (ProfileList.validId(id)) {
            try {
                for (ProfileList.Profile profile : list(context)) if (profile.id.equals(id)) return id;
            } catch (RuntimeException unreadable) { /* Do not overwrite a damaged catalogue. */ }
        }
        return ProfileList.DEFAULT;
    }
    static ProfileList.Profile active(Context context) {
        String id = activeId(context);
        for (ProfileList.Profile profile : list(context)) if (profile.id.equals(id)) return profile;
        return list(context).get(0);
    }
    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(settingsName(activeId(context)), Context.MODE_PRIVATE);
    }
    static String settingsName(String id) {
        if (!ProfileList.validId(id)) throw new IllegalArgumentException();
        return Config.SETTINGS_NAME + (ProfileList.DEFAULT.equals(id) ? "" : "_" + id);
    }
    static File file(Context context, String name) {
        String id = activeId(context);
        File root = ProfileList.DEFAULT.equals(id) ? context.getFilesDir()
                : new File(new File(context.getFilesDir(), "profiles"), id);
        return new File(root, name);
    }
    static boolean save(Context context, List<ProfileList.Profile> profiles) {
        boolean saved = store(context).edit().putString("list", ProfileList.encode(profiles)).commit();
        if (saved) changed(context);
        return saved;
    }
    static boolean select(Context context, String id) {
        for (ProfileList.Profile profile : list(context)) if (profile.id.equals(id)) {
            return store(context).edit().putString("active", id).commit();
        }
        return false;
    }
    static File avatarFile(Context context, String profileId, String avatarId) {
        if (!ProfileList.validId(profileId) || !ProfileList.validAvatar(avatarId) || avatarId.isEmpty())
            throw new IllegalArgumentException();
        return new File(new File(new File(context.getFilesDir(), "profiles"), profileId), "avatar-" + avatarId + ".webp");
    }
    static boolean picture(Context context, ProfileList.Profile profile, String avatar, android.graphics.Bitmap bitmap) {
        List<ProfileList.Profile> next = list(context);
        int index = -1;
        for (int i = 0; i < next.size(); i++) if (next.get(i).id.equals(profile.id)) index = i;
        if (index < 0) return false;
        if (!avatar.isEmpty()) {
            if (bitmap == null) return false;
            android.util.AtomicFile file = new android.util.AtomicFile(avatarFile(context, profile.id, avatar));
            java.io.FileOutputStream output = null;
            try {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP, 85, bytes) || bytes.size() > 128 * 1024) return false;
                output = file.startWrite();
                output.write(bytes.toByteArray());
                file.finishWrite(output);
            } catch (java.io.IOException failure) {
                if (output != null) file.failWrite(output);
                return false;
            }
        }
        next.set(index, new ProfileList.Profile(profile.id, profile.name, avatar));
        if (!save(context, next)) {
            if (!avatar.isEmpty() && !profile.avatar.equals(avatar)) avatarFile(context, profile.id, avatar).delete();
            return false;
        }
        if (!profile.avatar.isEmpty() && !profile.avatar.equals(avatar)) avatarFile(context, profile.id, profile.avatar).delete();
        return true;
    }
    private static void changed(Context context) {
        SharedPreferences choices = preferences(context);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY, choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }
}
