# Boox Pen POC

Smallest Android proof that a third-party app can draw on a **Boox Go 7 Color II** with stylus latency comparable to native Notes.

Standard `View` / `onTouch` drawing is too delayed on Boox. This app uses the **Onyx Pen SDK `TouchHelper` raw/scribble path**: live ink is rendered by the e-ink controller, not by Android's view drawing.

## What it does

Top to bottom:

1. **Scratchpad | Notebooks**: top-level nav, always visible.
2. **Pen / Eraser**: drawing toolbar.
3. Drawing area: a continuous vertical scroll of pages separated by a dashed line. Finger scrolls; the stylus draws.
   - **Scratchpad**: blank pages top to bottom, always ending with a blank page. Opens on that last page.
   - **Notebooks**: a row of notebook tabs (**comedy**, **journal**, **actions.life**, **miscellaneous**), then that notebook's pages in order.
4. Bottom-right of every page: **Move** and **Wipe**. Move opens a menu of notebooks; tapping one appends the page as the newest page of that notebook. Wipe clears that page only. Both are disabled on blank pages.

Live ink uses `TouchHelper.create` → `openRawDrawing` → `setRawDrawingEnabled(true)` on one `SurfaceView` under the page stack, with hardware render on. Live stroke style is **`TouchHelper.STROKE_STYLE_FOUNTAIN`**, base width **0.50mm** (Notes default) via `TypedValue.COMPLEX_UNIT_MM`. TouchHelper has no public setter for Notes pressure 30% or stroke stabilization 60%, so those stay firmware-default.

Completed strokes freeze to a per-page bitmap on pen-up. Eraser deletes whole strokes. Raw drawing pauses while scrolling or while the Move menu is open.

Pages are stored locally in `files/zetteldraw-boards.json`. Boards from v4 (the Inbox build) load as Scratchpad pages; filed boards stay in their notebooks.

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

Debug builds are signed with the committed `app/debug.keystore` (standard `android` / `androiddebugkey` debug credentials), so a build from any machine installs over any other without uninstalling.

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

4. Open **Boox Pen POC**. You land on the last blank Scratchpad page. Draw with the stylus; finger-scroll through pages. **Move** on a page, then a notebook name, files it. **Eraser** deletes strokes; **Wipe** clears one page.

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
- `CanvasActivity` — Scratchpad | Notebooks nav, Pen / Eraser toolbar, notebook tabs, page slots with Move / Wipe.
- `PageScroller` — finger scroll over the page stack; forwards stylus gestures to the ink surface.
- `PageInkView` — `SurfaceView` + `TouchHelper` live ink; paints the visible pages at the current scroll offset.
- `BoardStore` — local scratchpad + notebooks JSON.
- `InkRenderer` — pressure + end-taper freeze strokes and eraser hit-tests.
