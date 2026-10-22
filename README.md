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

- **Easy-first EQ** – 33 presets in four groups (Basics, Genres, Voice & places, Precise) with a
  one-line description of each, the active preset highlighted, **Quick tone** sliders (Bass / Mids /
  Treble) that work on top of any shape, Undo and Flatten; save your own presets ("Mine").
  Top right: **EQ profiles** (up to 10, named; save, load, rename, delete), **Undo** (the last change) and **Reset** (every gain back
  to 0 dB). **Reset to 10 bands** in Fine-tune brings back the standard 31 Hz–16 kHz layout,
  keeping your curve's shape; the **Preamp** has its own reset button (back to 0 dB, one undo step).
  Everything below stays one tap away under **Fine-tune**.
- **Parametric EQ** – any number of bands (1–64); every band has an exact frequency, gain, width (Q)
  and type (peak, low/high shelf, low/high cut). Drag the dots on the curve or type exact values.
- **Smooth curve** – knobs whose bells overlap (the 10 graphic bands, or any knobs close together)
  blend into one smooth line that goes exactly through every knob and glides to the next: no dip
  between two raised knobs, no peak above them. The sound follows that line too (the filters are
  fitted to it, within a few tenths of a dB), and the graph, headroom and Battery EQ use the same
  filters, so what you see is what you hear. A narrow knob (high Q) or one far from the others
  keeps its own bell.
- **Visualizer around the cover** – on the Playing tab the live spectrum rings a slightly smaller
  cover, in the Bars, Curve or Mirror style: **One loop** (bass at the top, once round clockwise to
  the treble), **Mirrored** (both sides alike, bass at the bottom) or **Mirrored, flipped** (bass at
  the top). Or behind the full-size cover: **From below** (the original), **From above** (hanging
  down), **From above, flipped** (bass on the right), or both at once: **Top & bottom, mirrored**
  (the top is a mirror image of the bottom) or **Top & bottom, not mirrored** (the top is the bottom
  turned round, bass on the right). Look > Visualizer > "On the Playing tab".
- **Freeze when music stops** (Look > Visualizer) – when a song is paused or ends, the visualizer
  stays exactly as it was instead of falling away, also after switching tabs; nothing runs while
  it's frozen.
- **Move with the beat** (Look > Visualizer) – the cover pumps on bass hits, hardest on drops and
  big bass moments (bass that jumps far above what it was doing lately), like remix videos; steady
  bass leaves it still. **Beat strength** sets how far it moves. It listens through the visualizer,
  so it rests while the audio chip plays the song by itself (Power-saving playback).
- **Cover opacity with bars** (Look > Glass) – with the visualizer behind the cover, the cover is
  this solid only where the bars pass behind it: keep the cover at 100% and still see the bars
  through it. Cover opacity still sets the rest of the cover.
- **Gradient flow** (Look > Visualizer) – like a TV's backlight: the screen round the cover glows in
  the colours of the cover's edges (each spot along an edge takes the average of the cover's
  outermost 10 pixels there). On AMOLED it melts into your own colour further out; on the other
  themes it fades into the background.
- **Icon mode** – pick a black-and-white picture (PNG works best; JPG and others too) and it takes
  the cover's place. The picture itself is never drawn: the live spectrum grows out of the outline
  of its dark parts, so the shape shows only through the bars. It follows every visualizer setting
  (style, layout along the outline, strength, "before EQ" outline, frame rate, detail, Battery
  Saver, Smoothness, accent colour). Bars go **outward** over the background, **inward** into the
  dark parts, or **both ways**; they stop short where the white space narrows (gaps between coils,
  curls) so they never meet or cover a dark part, and keep a safe margin from the screen's edge.
  The dark parts can be left **Dark** (empty), **Glass** (the app's glass in the play button's
  colour, following Blur / Frost / Rim light) or **Accent** (solid, like the play button). The
  **song's cover** can be **Off**, **Inside the icon** (in its dark parts) or **Full size**: the
  whole cover, as big as the normal one, stays behind the visualizer with adjustable **Cover
  dimming**. While nothing plays: **Show nothing**, **Keep the icon** or **Show the cover**. A **Black / white threshold** (with Undo and Reset), **Invert**
  and **Minimum hole size** (tiny enclosed white spots such as an eye's highlight are ignored)
  shape how the picture is traced, with a live preview. Saved in each Look profile.
  Look > Visualizer > "On the Playing tab" > Icon.
- **Live view** – the spectrum after the EQ, a faint outline of the sound before it, and a scrolling
  waveform. The waveform is **pre-made for every song** (decoded once, like an index: one point per
  10 ms) and drawn from the player's position, so it's exactly in sync and costs nothing to show —
  it even keeps moving in power-saving playback.
- **Lyrics** – found automatically for every song on the phone (LRCLIB, a free database of
  time-synced lyrics; matched by title, artist and length, with "Artist - Title" file names and
  "(Official Video)"-style clutter handled) and shown under the player like Spotify: the sung line
  is highlighted and followed, tap any line to jump there. Saved once, then they work offline.
- **Library preparation** – waveforms for the whole library are made while the phone charges,
  lyrics are fetched whenever you're online; the playing song is always done on the spot. Status
  and "Prepare now" on the Sound page.
- **Per-song EQ** – give any song its own EQ; it loads automatically whenever that song plays.
- **Pitch & speed** – independent (or linked "tape" mode), remembered per song, powered by the
  Rubber Band R3 engine (studio-grade). Pitch changes are heard live while dragging; speed is
  applied on release. At original pitch and speed the engine is bypassed bit-exactly. Optional
  battery-saving engine (Rubber Band R2) in Profile > App settings.
- **Clean signal path** – 64-bit EQ processing, automatic headroom so boosts never clip, a soft
  limiter just below 0 dBFS, 32-bit float output.
- **Output awareness** – shows where audio is going, warns when earbuds fall back to LE Audio (LC3)
  instead of LDAC, and offers **bit-perfect USB output** (Android 14+) for USB DACs. Tap the output
  chip at the top of the Playing tab for the **Sound** page (output device and signal path).
- **Liquid glass UI** – real refraction and blur; the bars at the bottom bend and blur the page as
  it scrolls under them (every theme, AMOLED included). Accent, background glow, blur, refraction,
  tint, rim light, corner roundness, background motion, album-art glow and the visualizer are all
  adjustable, and every change shows at once. Colours are set with independent Hue, Saturation
  and Brightness strips: each one changes only itself, at any brightness.
- **Smooth** – runs at the screen's highest refresh rate (120 Hz; "Highest refresh rate" in Profile,
  the way Metrolist does it), nothing caps the frame rate, the moving background animates every
  frame, and release builds are optimised with R8 (Compose needs it to run smoothly).
- **Smoothness** (Profile > App settings) – the same glass costs some phones far more than others. On
  **Automatic** the app watches how long its frames take; if it keeps stuttering it steps down by
  itself and remembers that for the phone:
  - **Full** – real blur everywhere, the bars at the bottom bend the page scrolling under them;
  - **Balanced** – the bars bend the background instead of the page, panels bend without blur,
    animations and visualizer at up to 60 fps;
  - **Light** – plain frosted panels (no blur or bending), animations and visualizer at up to
    30 fps.
  You can also pick a level yourself, or "Measure again from Full".
- **Look profiles** – up to 10, named, in the top right of the Look tab (rename, delete, and a "+"
  until there are 10), each keeping every Look setting (visualizer refresh rate and icon mode
  included) and showing which theme it uses; the one in use saves each change. The visualizer's
  **Detail** and the screen settings are the same in every profile, so switching never changes
  them. A new profile starts as a copy of the one in use. Picking a theme changes only its colours, glass and background —
  your visualizer, corners, refresh rate, bar settings and Album art glow stay (the glow is off
  until you turn it on) — and your own AMOLED colour comes back when you return to AMOLED or
  AMOLED Cover from another theme. **Reset** (top right, or at the bottom of the page) puts
  everything on the Look tab back to the default, for the profile in use or all of them.
- **Profile tab** – the last tab:
  - **Users** – up to 3 people on one phone, each with their own looks, EQs, settings and every
    song's own EQ, pitch and speed. Tap someone to switch; add, rename, delete, export and import.
  - **Look profiles** and **EQ profiles** – every profile with what it holds (theme, colours, glass,
    visualizer…; the EQ's curve, bands and level), to use, rename, export, delete, add or import.
  - **App settings** – the screen (highest refresh rate, Smoothness, hiding the system bars) and
    quality & battery (power-saving playback, Battery EQ, bit-perfect USB, pitch options).
  - **Export / import** – a look profile, an EQ profile or a whole user is a small file to keep,
    send or import on another phone (a look with an icon picture carries the picture). Any Import
    button takes any of them.
- **AMOLED themes** – **AMOLED** (default on new installs): pure black with a cyan accent (hue 180°);
  **AMOLED Cover**: the same pure black, with buttons, sliders and everything you can touch coloured
  from the current song's cover.
- **Full screen** – the navigation bar is hidden inside the app (swipe up from the bottom edge to
  bring it back); the status bar can be hidden too.
- **Dynamic colours** – the accent and background glow follow the average colour of the
  current song's cover, switching with the song (switch in Look).
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
  - **Power-saving playback** (on by default, in Profile > App settings): when the EQ is off or flat and pitch and
    speed are original, the phone's audio chip decodes and plays the file by itself (audio
    offload) and the main processor sleeps between big chunks of audio. Changing the EQ or pitch
    switches back to LToTS' own sound path instantly. It shows whether it's active (the chip
    has to support the file's format).
  - **Battery EQ** (experimental, in Profile > App settings): the EQ is played by the phone's own built-in EQ,
    fitted to its few fixed bands, so on phones whose EQ can run on the audio chip the chip keeps
    playing by itself even with an EQ on. The EQ tab draws the simplified curve dashed; the switch says
    live whether it's working on your phone.
  - the player only wakes when the audio output actually needs more sound, instead of every
    10 ms;
  - after 10 minutes paused, the decoder and audio output are released (play resumes exactly
    where it stopped);
  - the live spectrum's audio copy is only made while a spectrum is on screen;
  - the live spectrum and wave run at 120 fps by default (30/60/120/off in Look); everything to
    do with them is shut down completely when the screen turns off or the app goes to the
    background, and sleeps while nothing plays or while the audio chip plays directly;
  - they only analyse what's drawn (no wave on Now Playing, no separate "before EQ" analysis
    while the EQ is flat), measure only new audio for the wave each frame, and sit above the
    glass rather than inside it, so the glass isn't re-rendered for every frame;
  - the moving background only animates while the app is on screen (and not with Battery
    Saver); "Animations" off stops it. On the AMOLED themes it's grayed out (nothing moves on
    pure black), and the switch says when Battery Saver is pausing it.

## Roadmap

- **Phase 2** – YouTube Music streaming (play without downloading) and syncing your own library.
- **Phase 3** – Search by lyrics: the saved lyrics plus an on-device AI model for half-remembered
  lines (runs only while searching).

## Getting the best sound

- **WF-1000XM6 (Bluetooth):** in Developer options set the Bluetooth codec to **LDAC** and the LDAC
  playback quality to **Best quality (990 kbps)**. If LToTS shows **LC3**, the earbuds are using LE
  Audio — turn LE Audio off for them to get LDAC.
- **HD 599 (wired):** plug in through a USB-C DAC/dongle and turn on **Bit-perfect USB output** in
  Profile > App settings.

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
- the app's screens under Robolectric, with screenshots saved to `app/build/ui-shots/` (including
  icon mode, `22_icon_*`);
- icon mode's tracing: threshold and invert, transparency, speck removal, minimum hole size,
  outlines and normals, bar reach in narrow gaps and curls, and the spectrum's order along the
  outline — on drawn fixtures and on a reference snake picture that lives only in the tests
  (`app/src/test/resources/icons/`), not in the app;
- the Smoothness stutter detector (when it steps down, and when it must not);
- the smooth EQ curve (through every knob, no dips or overshoot, lone bells untouched, speed);
- look and EQ profiles (names, 10 at most, rename/delete, older saves) and users (each with their
  own everything, switching, deleting, export and import).

With `-Dltots.live=true` two extra tests talk to the real YouTube Music / YouTube (search and the
complete download pipeline with the bundled yt-dlp).

## Code map

```
app/src/main/java/com/ltots/app/
  audio/     EQ maths, the real-time EQ processor, analyzer/FFT, output & bit-perfect handling
  data/      local library, per-song profiles, presets, theme & settings storage (per user), users,
             EQ profiles, picked icons
  profiles/  switching user, exporting and importing looks, EQs and users
  ytm/       YouTube Music client: search, albums, playlists, artists, song details, links
  download/  yt-dlp engine, download queue, tagging/cover, background service
  playback/  background playback service + connection
  ui/        glass system, Smoothness (frame-time watcher), screens (Library, Playing, EQ, Get, Look,
             Profile, Sound), components
  ui/components/
    LiveVisual.kt       the analyzer loop (frame rate, Battery Saver, Smoothness, screen-off)
    SpectrumOutline.kt  points + outward normals + reach that a spectrum grows from (shared)
    CoverSpectrum.kt    CoverRing: the spectrum round the cover
    IconMask.kt         icon mode: picture → black/white mask → smooth outlines → distance fields
    IconRing.kt         icon mode: the outlines placed on screen, bar reach/width, band order
    IconSpectrum.kt     icon mode: loading/caching, drawing, fills, idle states
  ui/screens/LookIconSettings.kt  the icon controls in Look
app/src/main/cpp/  Rubber Band (pitch/speed engine) + CMake build
app/src/test/  audio-chain tests, YouTube Music parser tests, colour tests, icon-mode tests, UI tests
```
