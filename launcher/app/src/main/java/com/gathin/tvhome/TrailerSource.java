package com.gathin.tvhome;

/** Public page identity and conservative preview selection; independent of Android playback. */
final class TrailerSource {
    static String page(String intent, boolean movie, String homepage) {
        // Prefer TMDB's identified work homepage over provider episode IDs or title guesses.
        java.util.regex.Pattern appleId = java.util.regex.Pattern.compile("umc\\.cmc\\.[A-Za-z0-9]+");
        if (homepage != null) {
            try {
                java.net.URI uri = new java.net.URI(homepage);
                if ("tv.apple.com".equalsIgnoreCase(uri.getHost())) {
                    java.util.regex.Matcher id = appleId.matcher(uri.getPath());
                    if (id.find()) {
                        boolean show = uri.getPath().contains("/show/");
                        return "https://tv.apple.com/us/" + (show ? "show" : "movie") + "/_/" + id.group();
                    }
                }
            } catch (java.net.URISyntaxException ignored) { /* Fall back to the app's identifier. */ }
        }
        java.util.regex.Matcher id = appleId.matcher(intent == null ? "" : intent);
        return id.find() ? "https://tv.apple.com/us/" + (movie ? "movie" : "show") + "/_/" + id.group() : null;
    }
    static String previewMetrics(String html) {
        java.util.regex.Matcher entries = java.util.regex.Pattern.compile("\"mediaMetrics\":(\\{[^{}]{1,8000}\\})").matcher(html);
        while (entries.find()) {
            String value = entries.group(1);
            if (!value.contains("\"contentType\":\"Extra\"")) continue;
            java.util.regex.Matcher duration = java.util.regex.Pattern.compile("\"duration\":([0-9]+)").matcher(value);
            if (duration.find()) {
                long ms = Long.parseLong(duration.group(1));
                if (ms >= 10000 && ms <= 300000) return value;
            }
        }
        return null;
    }
    static String variant(String master) {
        String[] lines = master.split("\\r?\\n");
        String best = null; int bestWidth = 0;
        for (int i = 0; i + 1 < lines.length; i++) {
            String line = lines[i];
            if (!line.startsWith("#EXT-X-STREAM-INF:") || !line.contains("avc1")
                    || !line.contains("VIDEO-RANGE=SDR")) continue;
            java.util.regex.Matcher size = java.util.regex.Pattern.compile("RESOLUTION=([0-9]+)x([0-9]+)").matcher(line);
            if (!size.find()) continue;
            int width = Integer.parseInt(size.group(1));
            if (width > bestWidth && width <= 1280 && Integer.parseInt(size.group(2)) <= 720
                    && lines[i + 1].startsWith("https://vod-") && lines[i + 1].contains(".tv.apple.com/itunes-assets/VideoPreview")) {
                best = lines[i + 1].trim(); bestWidth = width;
            }
        }
        return best;
    }
    static String audioVariant(String master) {
        String[] lines = master.split("\\r?\\n");
        int bestWidth = 0;
        String bestAudioGroup = null;
        for (int i = 0; i + 1 < lines.length; i++) {
            String line = lines[i];
            if (!line.startsWith("#EXT-X-STREAM-INF:") || !line.contains("avc1")
                    || !line.contains("VIDEO-RANGE=SDR")) continue;
            java.util.regex.Matcher size = java.util.regex.Pattern.compile("RESOLUTION=([0-9]+)x([0-9]+)").matcher(line);
            if (!size.find()) continue;
            int width = Integer.parseInt(size.group(1));
            if (width > bestWidth && width <= 1280 && Integer.parseInt(size.group(2)) <= 720
                    && lines[i + 1].startsWith("https://vod-") && lines[i + 1].contains(".tv.apple.com/itunes-assets/VideoPreview")) {
                bestWidth = width;
                java.util.regex.Matcher audio = java.util.regex.Pattern.compile("AUDIO=\"([^\"]+)\"").matcher(line);
                bestAudioGroup = audio.find() ? audio.group(1) : null;
            }
        }
        if (bestAudioGroup != null) {
            String fallback = null;
            for (String line : lines) {
                if (line.startsWith("#EXT-X-MEDIA:TYPE=AUDIO") && line.contains("GROUP-ID=\"" + bestAudioGroup + "\"")) {
                    java.util.regex.Matcher uri = java.util.regex.Pattern.compile("URI=\"([^\"]+)\"").matcher(line);
                    if (uri.find()) {
                        String found = uri.group(1).trim();
                        if (line.contains("DEFAULT=YES")) return found;
                        if (fallback == null) fallback = found;
                    }
                }
            }
            if (fallback != null) return fallback;
        }
        for (String line : lines) {
            if (line.startsWith("#EXT-X-MEDIA:TYPE=AUDIO") && line.contains("DEFAULT=YES")) {
                java.util.regex.Matcher uri = java.util.regex.Pattern.compile("URI=\"([^\"]+)\"").matcher(line);
                if (uri.find() && uri.group(1).contains(".tv.apple.com/itunes-assets/VideoPreview")) {
                    return uri.group(1).trim();
                }
            }
        }
        return null;
    }
}
