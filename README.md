# LToTS

A music player for Android built around sound quality and control: a fully parametric EQ with a
live spectrum, per-song settings, independent pitch & speed, and a liquid-glass interface you can
restyle completely.

Built for a Xiaomi 17 Ultra with Sony WF-1000XM6 earbuds and Sennheiser HD 599 headphones.

## Install

Download **LToTS.apk** from the [latest release](https://github.com/OctoPas647/ltots/releases/latest)
on your phone and open it. Every push to `main` builds a new release automatically; installing a
newer APK updates the app and keeps your settings.

## What's in phase 1

- **Parametric EQ** – any number of bands (1–64); every band has an exact frequency, gain, width (Q)
  and type (peak, low/high shelf, low/high cut). Drag the dots on the curve or type exact values.
- **Live view** – the spectrum after the EQ, a faint outline of the sound before it, and a scrolling
  waveform, all synced to what you're hearing.
- **Per-song EQ** – give any song its own EQ; it loads automatically whenever that song plays.
- **Pitch & speed** – independent (or linked "tape" mode), remembered per song, powered by the
  Rubber Band R3 engine (studio-grade). Pitch changes are heard live while dragging; speed is
  applied on release. At original pitch and speed the engine is bypassed bit-exactly. Optional
  battery-saving engine (Rubber Band R2) in Sound.
- **Clean signal path** – 64-bit EQ processing, automatic headroom so boosts never clip, a soft
  limiter just below 0 dBFS, 32-bit float output.
- **Output awareness** – shows where audio is going, warns when earbuds fall back to LE Audio (LC3)
  instead of LDAC, and offers **bit-perfect USB output** (Android 14+) for USB DACs.
- **Liquid glass UI** – real refraction and blur; colours, blur, refraction, tint, rim light, corner
  roundness, background motion, album-art glow and visualizer style are all adjustable.
- **AMOLED theme** (default on new installs) – pure black with an electric-cyan accent; "Pure black"
  can also be switched on for any theme.
- **Full screen** – the navigation bar is hidden inside the app (swipe up from the bottom edge to
  bring it back); the status bar can be hidden too.
- **Dynamic colours** – the accent and background glow follow the average colour of the
  current song's cover, fading between songs (switch in Look).
- **Downloads ("Get" tab)** – search YouTube Music or paste/share a link to a song, album,
  playlist or artist:
  - YouTube's best audio (Opus) kept exactly as sent — no re-encoding;
  - official album metadata (title, artists, album, album artist, track number, year) and the
    full-size cover embedded in every file;
  - music videos swapped for the official audio release when one exists;
  - queue with several downloads at once, background downloading with a notification,
    retry/cancel, history, and playlists you can keep in sync;
  - files saved to `Music/LToTS/Artist/Album/`, visible to any app;
  - built on yt-dlp (bundled, updatable in-app with "Update engine"); "Test engine" checks the
    whole download engine on the phone, and tapping a failed download shows (and copies) exactly
    what went wrong.
- Local library with songs, albums, artists and search.
- **Battery-friendly**
  - **Power-saving playback** (on by default, in Sound): when the EQ is off or flat and pitch and
    speed are original, the phone's audio chip decodes and plays the file by itself (audio
    offload) and the main processor sleeps between big chunks of audio. Changing the EQ or pitch
    switches back to LToTS' own sound path instantly. Sound shows whether it's active (the chip
    has to support the file's format).
  - the player only wakes when the audio output actually needs more sound, instead of every
    10 ms;
  - after 10 minutes paused, the decoder and audio output are released (play resumes exactly
    where it stopped);
  - the live spectrum's audio copy is only made while a spectrum is on screen;
  - the live spectrum and wave run at 30 fps by default (30/60/120/off in Look); everything to
    do with them is shut down completely when the screen turns off or the app goes to the
    background, and sleeps while nothing plays or while the audio chip plays directly;
  - they only analyse what's drawn (no wave on Now Playing, no separate "before EQ" analysis
    while the EQ is flat), measure only new audio for the wave each frame, and sit above the
    glass rather than inside it, so the glass isn't re-rendered for every frame;
  - the moving background updates 20 times a second and only while the app is on screen.

## Roadmap

- **Phase 2** – YouTube Music streaming (play without downloading) and syncing your own library.
- **Phase 3** – Search by lyrics: a lyrics database plus an on-device AI model for half-remembered
  lines (runs only while searching).

## Getting the best sound

- **WF-1000XM6 (Bluetooth):** in Developer options set the Bluetooth codec to **LDAC** and the LDAC
  playback quality to **Best quality (990 kbps)**. If LToTS shows **LC3**, the earbuds are using LE
  Audio — turn LE Audio off for them to get LDAC.
- **HD 599 (wired):** plug in through a USB-C DAC/dongle and turn on **Bit-perfect USB output** in
  the Sound tab.

## Build setup (for the GitHub Actions release)

Releases are signed with a private key stored as repository secrets
(*Settings → Secrets and variables → Actions → New repository secret*):

| Secret | Value |
| --- | --- |
| `LTOTS_KEYSTORE_BASE64` | the keystore file, base64-encoded |
| `LTOTS_KEYSTORE_PASSWORD` | keystore password |
| `LTOTS_KEY_ALIAS` | `ltots` |
| `LTOTS_KEY_PASSWORD` | key password |

Without them the workflow still builds and tests the app but doesn't publish a release.

## Licence note

LToTS includes the [Rubber Band Library](https://breakfastquay.com/rubberband/) (GPL v2 or later)
in `app/src/main/cpp/rubberband`. Anyone distributing the app therefore has to do so under
GPL-compatible terms.

## Tests

`./gradlew :app:testDebugUnitTest` runs, on the desktop JVM:
- the real audio chain driven like Media3's audio output (pitch accuracy, live pitch dragging,
  speed changes, per-song switching at song boundaries, EQ crossfades, limiter, bit-perfect);
- the real ExoPlayer + Media3 audio output + Rubber Band playing through a real-time output,
  while speed and pitch change (position must keep moving at the right rate, no gaps);
- the YouTube Music parser against captured real responses;
- the app's screens under Robolectric, with screenshots saved to `app/build/ui-shots/`.

With `-Dltots.live=true` two extra tests talk to the real YouTube Music / YouTube (search and the
complete download pipeline with the bundled yt-dlp).

## Code map

```
app/src/main/java/com/ltots/app/
  audio/     EQ maths, the real-time EQ processor, analyzer/FFT, output & bit-perfect handling
  data/      local library, per-song profiles, presets, theme & settings storage
  ytm/       YouTube Music client: search, albums, playlists, artists, song details, links
  download/  yt-dlp engine, download queue, tagging/cover, background service
  playback/  background playback service + connection
  ui/        glass system, screens (Library, Playing, EQ, Look, Sound), components
app/src/main/cpp/  Rubber Band (pitch/speed engine) + CMake build
app/src/test/  audio-chain tests, YouTube Music parser tests, colour tests, UI tests
```
