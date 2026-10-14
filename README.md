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
- **Pitch & speed** – independent (or linked "tape" mode), remembered per song.
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
- Local library with songs, albums, artists and search.

## Roadmap

- **Phase 2** – YouTube Music: sign in, library/playlists/albums sync, streaming, downloads, and
  picking the best available version of each song.
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

## Code map

```
app/src/main/java/com/ltots/app/
  audio/     EQ maths, the real-time EQ processor, analyzer/FFT, output & bit-perfect handling
  data/      local library, per-song profiles, presets, theme & settings storage
  playback/  background playback service + connection
  ui/        glass system, screens (Library, Playing, EQ, Look, Sound), components
app/src/test/  audio-path tests: test tones through EQ → pitch/speed, measured numerically
```
