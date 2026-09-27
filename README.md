# Boox Pen POC

Smallest Android proof that a third-party app can draw on a **Boox Go 7 Color II** with stylus latency comparable to native Notes.

Standard `View` / `onTouch` drawing is too delayed on Boox. This app uses the **Onyx Pen SDK `TouchHelper` raw/scribble path**: live ink is rendered by the e-ink controller, not by Android's view drawing.

## What it does

Top to bottom:

1. **One top bar**: `[sheet icon] | comedy  journal  actions.life  miscellaneous … +`. The Scratchpad is a blank-sheet icon pinned left. The notebook tabs scroll sideways, and **+** (new notebook) sits right after the last tab and scrolls with them. The open item gets a 2 dp black underline (and a bold label); nothing is filled.
   - Hold a notebook tab to rename or delete it: the menu drops down anchored under that tab (it also opens the notebook). Rename edits the name in the same anchored panel; Delete confirms there too, and the notebook's pages move back to the end of the Scratchpad, with no page deleted. **+** opens its name field under the **+**.
   - Design choice: no ⋯ in the bar, so it stays calm; anchoring every menu and form to the tab (or page) it acts on keeps it unambiguous what it affects.
2. **Undo · Redo | Pen · Eraser · Lasso**: icon toolbar (Lucide icons as vector drawables, black on white). The active tool has the same 2 dp black underline; Undo / Redo fade when there is nothing to do. A hint line on the left says what the Lasso is doing.
   - Style: controls are borderless (toolbar icons, +, Move, the page ⋯, menu rows), with tap targets of at least 48 dp. A menu or form is one thin frame with plain rows inside, never a box inside a box.
   - Undo / redo covers pen strokes, eraser passes (one pass is one step, even across two pages) and lasso moves. Edits are recorded by stroke id (strokes added, removed with their index, or replaced when moved), so an older step still applies cleanly after other strokes changed. The stack holds 50 steps for the open page list and is cleared when another list opens or the app restarts. Each undo / redo saves the page through the repository like a pen-up, so sync just sees a normal save. Undoing the first stroke on the last page collapses the extra blank page again; redo brings it back.
   - Wipe and Delete page are not in the history: they already confirm first, and undoing a Delete would need un-deleting a synced tombstone.
3. Drawing area: a continuous vertical scroll of pages separated by a dashed line. Finger scrolls; the stylus draws.
   - The Scratchpad and every notebook are the same kind of page list: pages in order, always ending with one blank page. The first stroke on that blank stores it (and syncs it) at the next position in that list, and a new blank appears after it. An empty notebook shows one blank page to draw on. The Scratchpad opens on its last page; a notebook opens on its first.
   - A page is as tall as the drawing area under the two bars (minus the gap), so one whole page and its controls fit on screen. On the Go 7 Color II (1264×1680, xhdpi, full screen) that is **1264×1420**. Pages from before v10 whose ink reaches below that keep their old full-window height (1264×1680 there), so no ink is cropped or shifted.
4. Bottom of every page: the page number (1, 2, 3 … within that list) on the left; **Move** and **⋯** on the right. Move opens a menu of notebooks plus **+ New notebook** and appends the page as the newest page there (disabled on blank pages). **⋯** opens **Wipe** (clears the ink; only on pages with ink) and **Delete page**, anchored to that page, and each confirms in the same spot. Delete tombstones the page so it syncs, and works on blank pages too. A list's trailing blank page can't disappear: deleting it just leaves a fresh one.

**Lasso** (select and move ink on one page):

- Tap **Lasso**, then circle ink. The firmware draws the outline dashed, with no lag. A stroke is selected when at least half its points are inside; a dot when its point is inside.
- The selection gets a dashed box, and raw drawing pauses. Drag the box with the pen or a finger. During the drag only a moved bitmap is repainted, in fast monochrome mode. The box stays on its page.
- On release the strokes' points are rewritten (stroke ids kept), the area gets one clean partial refresh, the page is saved through the repository like a pen-up, and the tool switches back to **Pen**. Raw drawing resumes after 500 ms, and the side-button eraser is set up again.
- Tap outside the box to cancel. **Undo** puts a lasso move back; **Redo** moves it again.

Live ink uses `TouchHelper.create` → `openRawDrawing` → `setRawDrawingEnabled(true)` on one `SurfaceView` under the page stack, with hardware render on. Live stroke style is **`TouchHelper.STROKE_STYLE_FOUNTAIN`**, base width **0.50mm** (Notes default) via `TypedValue.COMPLEX_UNIT_MM`. TouchHelper has no public setter for Notes pressure 30% or stroke stabilization 60%, so those stay firmware-default.

Completed strokes freeze to a per-page bitmap on pen-up. Eraser deletes whole strokes. Raw drawing pauses while scrolling or while a menu or form is open. Menus and forms are drawn inside the window because separate popup windows do not show over the TouchHelper surface on Boox.

## Storage and sync

Local-first, as designed in the project's storage design doc:

- **SQLite (Room)** is the source of truth on the device. A pen-up snapshots the page's strokes and returns; one writer thread writes the ink file (staged and fsync'd) and then commits it with its row, newest snapshot per page wins. The UI thread never waits on ink writes, sync or the network.
- **Strokes and navigation**: the Onyx SDK posts pen callbacks to the main thread, so each stroke is converted with the page layout and scroll that were on screen when it was drawn (`InkViewport`), and scrolls or page-list changes apply only after strokes already queued. Debug builds log main-thread I/O (StrictMode) and stalls (`adb logcat -s zd-stall zd-repo`).
- **Ink files**: one compact binary stroke file per board in `files/ink/<board-id>.zdi`, with a deflated body of float32 points. Rows keep only the sha256 and the size. Format version 2 stores a stable 128-bit id per stroke; version 1 files still load, with ids derived from each stroke's index and points so every device gets the same ones (layout in `data/InkCodec`).
- Tables `notebooks` and `boards` match Postgres column for column (`server/migrations/`). A unit test fails if they drift. There are also device-only tables: `outbox` (rows still to push) and `sync_state` (the pull cursor).
- Ids are client UUIDs. Order uses fractional `position` keys (`a0`, `a0V`, …), so appending as newest or moving a page touches one row.
- Deletes are tombstones (`deleted_at`). Conflicts are last-write-wins per row. A losing board with different ink is kept as a hidden conflict copy (`conflict_of`), so ink is never dropped.
- **Backup without a server**: ink files plus `index.json` are mirrored to `Documents/zetteldraw/`, which survives an uninstall.
- **First launch migration**: an existing `files/zetteldraw-boards.json` (v4/v5) is imported with the same board ids and order. It is then renamed to `zetteldraw-boards.json.migrated` and kept as a fallback.

**Sync is off until a server URL is set.** Build with one:

```bash
./gradlew :app:assembleDebug -Pzetteldraw.syncUrl=http://<mac-lan-ip>:8787 -Pzetteldraw.syncToken=dev-device-token
```

With a URL set, WorkManager syncs every 15 minutes when the network is up, and again each time the app goes to the background. Failures retry with backoff. `SyncConfig.save()` can set the URL at runtime for a future settings screen.

## Sync server (`server/`)

TypeScript on Node 22 (Fastify, `pg`, AWS S3 client). Metadata goes in Postgres; ink blobs go in S3-compatible storage (MinIO locally) at `ink/<sha256>`. Auth is a bearer device token. See [server/README.md](server/README.md) for the protocol.

```bash
cd server
docker compose up -d --build --wait     # Postgres + MinIO + service on :8787
curl localhost:8787/healthz
```

Tests:

```bash
cd server && npm ci && npm test                   # unit: LWW, validation, migrations
WITH_ANDROID=1 npm run test:integration           # compose up, push/pull round trip, Android client end-to-end, compose down
cd .. && ./gradlew :app:testDebugUnitTest         # Android: repository, migration, positions, conflicts, schema contract
```

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

4. Open **Boox Pen POC**. You land on the last blank Scratchpad page. Draw with the stylus; finger-scroll through pages. **Move** on a page, then a notebook name, files it. **Eraser** deletes strokes; a page's **⋯** wipes or deletes it.

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
- `CanvasActivity` — the one top bar (Scratchpad, notebook tabs with their anchored menu, +), Undo / Redo / Pen / Eraser / Lasso icon toolbar, page slots with page number, Move and ⋯ (Wipe / Delete).
- `PageScroller` — finger scroll over the page stack; forwards stylus gestures to the ink surface, and every touch while a lasso selection is up.
- `PageInkView` — `SurfaceView` + `TouchHelper` live ink; paints the visible pages at the current scroll offset. Owns raw-drawing pause/resume, and the lasso selection, drag preview and commit.
- `Lasso` — lasso hit-test, offset clamping, move (rewrites points, keeps ids).
- `InkHistory` — undo / redo stack of stroke edits by id (add, remove, move), capped at 50.
- `InkRenderer` — pressure + end-taper freeze strokes and eraser hit-tests.
- `data/BoardRepository` — what the UI calls: one `pages(notebookId)` list for the Scratchpad (`null`) and each notebook, always ending with a blank page; save ink, move, wipe, delete pages, and create / rename / delete notebooks. `RoomBoardRepository` implements it with Room, ink files and the Documents mirror.
- `data/LegacyBoardImporter` — one-time import of the old `zetteldraw-boards.json`.
- `sync/` — `SyncEngine` (push the outbox, then pull until caught up), `SyncClient` (HTTP), `SyncWorker` / `SyncScheduler` (WorkManager), `SyncConfig`.
- `server/` — sync service, shared SQL migrations, docker-compose.
