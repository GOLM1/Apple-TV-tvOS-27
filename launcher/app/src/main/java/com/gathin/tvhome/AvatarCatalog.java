package com.gathin.tvhome;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Public image references only: no Netflix account IDs, cookies or profile-changing scripts. */
final class AvatarCatalog {
    static final class Entry {
        final String id, group, name, url;
        Entry(String id, String group, String name, String url) {
            this.id = id; this.group = group; this.name = name; this.url = url;
        }
    }
    static boolean validUrl(String value) {
        try {
            URI uri = new URI(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null
                    && uri.getHost().endsWith(".nflxso.net") && uri.getUserInfo() == null
                    && uri.getPort() == -1 && uri.getFragment() == null;
        } catch (Exception invalid) { return false; }
    }
    static List<Entry> parse(String text) {
        List<Entry> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        if (text.length() > 128 * 1024) throw new IllegalArgumentException();
        for (String line : text.split("\n")) {
            String[] row = line.split("\t", -1);
            if (row.length != 4 || row[0].isEmpty() || !ProfileList.validAvatar(row[0])
                    || row[1].isEmpty() || row[2].isEmpty() || !validUrl(row[3]) || !ids.add(row[0]))
                throw new IllegalArgumentException();
            entries.add(new Entry(row[0], row[1], row[2], row[3]));
        }
        return entries;
    }
}
