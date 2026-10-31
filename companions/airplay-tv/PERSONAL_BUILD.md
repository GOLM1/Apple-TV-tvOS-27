# AirPlay companion for this launcher

Standalone receiver based on [besliky/airplay-tv](https://github.com/besliky/airplay-tv).
This source snapshot includes local changes through `c2ab15e`, including the `db63bef` remote-control fix.
Launcher sources match the local `38223a2` build. Both installed builds passed their unit tests and Lint
before installation (342 launcher tests, 40 companion tests).

## Build

Use JDK 17, Android SDK 36/build-tools 36, NDK 28.2.13676358 and CMake 3.22.1 for the companion.
From this directory:

```
./gradlew --no-daemon testTvReleaseUnitTest lintTvRelease
./gradlew --no-daemon assembleTvRelease
```

Use the `armeabi-v7a` APK on a 32-bit TV. Build the launcher from the repository's `launcher/` directory.
Both projects use the local debug signing key by default; their signatures must match for the
signature-protected media bridge. Never publish the launcher's APK, which can contain your TMDB token.
No APK, signing key, local.properties, saved device data or personal photo is included here.

## Behaviour

- The receiver advertises as AirPlay TV Test, uses an available port and defaults to no boot autostart.
- Music > Open full player automatically defaults off. Home can show music without opening the full player.
- Music > Lyrics is optional; LRCLIB receives the song title and artist when enabled. Coverage varies.
- Music > Lyrics timing adjusts synced lyric display in 250 ms steps; it does not alter the audio.
- The player stays stationary. Lyrics use line timestamps; word-level timing is not inferred.
- Keep the original built-in receiver enabled. No root, firmware patch or replacement is required.
- In-app upstream updates are not the distribution path for this custom build; rebuild from this source.

The companion remains a separate GPL-3.0-or-later program. See LICENSE, NOTICE and THIRD_PARTY_LICENSES.md.
Upstream documentation and third-party source notices are retained; upstream GitHub automation is omitted.
