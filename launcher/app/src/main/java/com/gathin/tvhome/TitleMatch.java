package com.gathin.tvhome;

import java.text.Normalizer;
import java.util.Locale;

/** Conservative title matching: never substitute a popular but differently named work. */
final class TitleMatch {
    static String normalized(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll(" +", " ");
    }
    static boolean same(String query, String name, String original) {
        String q = normalized(query);
        return !q.isEmpty() && (q.equals(normalized(name)) || q.equals(normalized(original)));
    }
    static boolean arabic(String s) { return s != null && s.matches("(?s).*[\\u0600-\\u06ff].*"); }
    static boolean corroborated(String a, String b) {
        java.util.Set<String> words = new java.util.HashSet<>();
        for (String w : normalized(a).split(" ")) if (w.length() >= 4) words.add(w);
        int matched = 0;
        for (String w : new java.util.HashSet<>(java.util.Arrays.asList(normalized(b).split(" "))))
            if (words.contains(w)) matched++;
        return matched >= 3;
    }
}
