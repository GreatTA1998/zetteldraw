# Boox Pen POC

Smallest Android proof that a third-party app can draw on a **Boox Go 7 Color II** with stylus latency comparable to native Notes.

Standard `View` / `onTouch` drawing is too delayed on Boox. This app uses the **Onyx Pen SDK `TouchHelper` raw/scribble path**: live ink is rendered by the e-ink controller, not by Android's view drawing.

## What it does

1. Opens a full-screen white `SurfaceView` with tiny **Eraser** and **Wipe** controls.
2. Calls `TouchHelper.create` → `openRawDrawing` → `setRawDrawingEnabled(true)` with hardware render on.
3. Live stroke style is **`TouchHelper.STROKE_STYLE_FOUNTAIN`** (same id as `EpdController.STROKE_STYLE_BRUSH`), so hardware ink can vary with pressure. Base width is **4.75px**.
4. Completed strokes are copied into a bitmap and frozen on pen-up so they survive a refresh. The freeze is variable-width: pressure when it varies, plus thin–thick–thin end taper so flat pressure still looks like a pen, not a marker.
5. Eraser deletes whole strokes by hit-testing the stroke list (not pixel smear). The stylus eraser / `shortcutErase` path does the same. Wipe clears the page.

Live drawing stays on TouchHelper. The bitmap freeze is not the live drawing path.

Tested target: Boox Go 7 Color II (Android 13, Kaleido 3, optional InkSense stylus).

## Build

Requires JDK 17+, Android SDK platform 34, and network access to Onyx's Maven repo (HTTP):

`http://repo.boox.com/repository/maven-public/`

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug
```

APK:

`app/build/outputs/apk/debug/app-debug.apk`

The Boox Maven host is HTTP-only. Gradle is already allowed to use that insecure repo. Do not switch it to HTTPS; the server does not serve the artifacts that way.

SDK coordinates (already in `app/build.gradle.kts`):

- `com.onyx.android.sdk:onyxsdk-pen:1.5.5`
- `com.onyx.android.sdk:onyxsdk-device:1.3.6`

`onyxsdk-pen` ships `libonyx_pen_touch_reader.so` for `arm64-v8a` (the Go 7 Color II ABI). No extra device `.so` files need to be copied into this repo.

## Install on a Go 7 Color II

1. On the device: **Settings → More settings → Developer options → USB debugging**.
2. Connect USB (or `adb connect <ip>:5555` after wireless debugging).
3. Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Sideload without adb: copy the APK to internal storage and open it in **Storage**. Allow install from that source if prompted.

4. Open **Boox Pen POC**. Draw with the stylus. Compare latency to **Notes**. Use **Eraser** to delete strokes, **Wipe** to clear the page.

If the screen stays white and nothing appears under the pen:

- Confirm this is a stylus-capable Go 7 Color II firmware, not a finger-only Go Color 7 Gen 1.
- Check `adb logcat | grep -i -e TouchHelper -e onyx -e penpoc`.
- Hidden API exemption is already applied in `PenApp`. Do not remove it; TouchHelper needs it on Android 13.

## Remaining device step

None for a normal Gradle build: the Pen SDK is pulled from `repo.boox.com` at compile time.

If that Maven host is blocked, download the AAR yourself and drop it in `app/libs/`:

`http://repo.boox.com/repository/maven-public/com/onyx/android/sdk/onyxsdk-pen/1.5.5/onyxsdk-pen-1.5.5.aar`

Then point `implementation` at `files("libs/onyxsdk-pen-1.5.5.aar")` plus the transitive `onyxsdk-device` / `onyxsdk-base` artifacts from the same repo. The Java wiring in `CanvasActivity` does not change.

## Layout

- `PenApp` — Hidden API bypass required by Onyx on Android 11+.
- `CanvasActivity` — `SurfaceView` + `TouchHelper` + pen-up bitmap freeze + Eraser/Wipe.
- `InkRenderer` — pressure + end-taper freeze strokes and eraser hit-tests.
