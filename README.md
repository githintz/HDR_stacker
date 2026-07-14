# HDR Stacker

An Android app that merges bracketed **Nikon NEF** RAW exposures into a single
HDR image. Point it at three (or more) shots of the same scene taken at
different exposures, and it decodes, aligns, and fuses them into one
well-exposed JPEG saved to your gallery.

<p align="center"><em>Decode RAW → align → fuse → save.</em></p>

## How it works

```
 NEF files (SAF picker)
        │
        ▼
 ┌─────────────────────────┐   native (C++/JNI)
 │ LibRaw                   │   demosaic + white balance + sRGB, per frame,
 │  → RGB pixels + shutter  │   with auto-brighten OFF so brackets keep their
 └─────────────────────────┘   true relative exposure
        │
        ▼
 ┌─────────────────────────┐   Kotlin + OpenCV (Java bindings)
 │ AlignMTB                 │   register hand-held frames
 │ MergeMertens  (default)  │   exposure fusion — no exposure times needed
 │  or  Debevec + Mantiuk   │   true radiance HDR from shutter speeds, tonemapped
 └─────────────────────────┘
        │
        ▼
 JPEG → Pictures/HDRStacker
```

Two merge modes, selectable in the UI:

- **Exposure fusion (Mertens)** — the default. Fast and robust; it blends the
  best-exposed regions of each frame directly into a displayable image. No
  exposure metadata required.
- **True HDR + tonemap (Debevec)** — reconstructs scene radiance using each
  frame's shutter speed (read straight from the NEF by LibRaw), then applies a
  Mantiuk tonemap. Falls back to an assumed 2-EV bracket spacing if shutter
  metadata is missing.

There's also a **half-resolution** toggle — decodes at ¼ the pixels, which is
much lighter on memory and useful for large brackets on modest phones.

## Project layout

| Path | Purpose |
|------|---------|
| `app/src/main/cpp/native-hdr.cpp` | JNI bridge: LibRaw → RGB pixels + shutter |
| `app/src/main/cpp/CMakeLists.txt` | Fetches & builds LibRaw for the NDK |
| `app/src/main/java/com/hdrstacker/NativeHdr.kt` | Native method declarations |
| `app/src/main/java/com/hdrstacker/HdrEngine.kt` | Decode → align → fuse → save pipeline |
| `app/src/main/java/com/hdrstacker/HdrViewModel.kt` | UI state + orchestration |
| `app/src/main/java/com/hdrstacker/MainActivity.kt` | Jetpack Compose UI |

## Building

Requirements:

- **Android Studio** (Ladybug / 2024.2 or newer) with the **Android SDK**,
  **NDK**, and **CMake** components installed (SDK Manager → SDK Tools).
- A device or emulator running **Android 10 (API 29)** or newer.
- **Network access on the first native build** — the CMake script downloads and
  compiles LibRaw the first time. OpenCV comes from Maven Central
  (`org.opencv:opencv:4.11.0`, which ships the native `.so`).

Then:

```bash
# Tell Gradle where your SDK lives (Android Studio does this automatically):
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Or just open the project in Android Studio and press Run.

### Offline / vendored LibRaw

The first native build fetches LibRaw from libraw.org and the CMake overlay from
GitHub. To build without network access, vendor LibRaw yourself and adjust
`app/src/main/cpp/CMakeLists.txt` to `add_subdirectory` your local copy instead
of the `FetchContent` block.

## Usage

1. Launch the app and tap **Add NEF frames**.
2. Select 2+ bracketed NEF files (dark / normal / bright of the same scene).
3. Pick a merge method and (optionally) enable half resolution.
4. Tap **Create HDR**. The result is written to **Pictures/HDRStacker** and can
   be opened straight from the app.

## Notes & limitations

- Best results come from a tripod or steady hand — AlignMTB corrects small
  translational shifts but not large motion, rotation, or moving subjects
  (which can ghost).
- Full-resolution decoding of several 24–45 MP frames is memory-hungry; use the
  half-resolution toggle if you hit `OutOfMemoryError` on older devices.
- LibRaw decodes essentially every RAW format, so CR2/CR3/ARW/RAF/DNG etc. also
  work even though the UI copy says "NEF".

## Licensing

LibRaw is dual-licensed (LGPL 2.1 / CDDL); this project builds it **without** the
GPL demosaic-pack, so the resulting binary is redistributable under the LGPL.
OpenCV is Apache 2.0. Review each dependency's license before shipping.
