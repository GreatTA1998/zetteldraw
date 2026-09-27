# Boox Pen POC

Smallest Android proof that a third-party app can draw on a **Boox Go 7 Color II** with stylus latency comparable to native Notes.

Standard `View` / `onTouch` drawing is too delayed on Boox. This app uses the **Onyx Pen SDK `TouchHelper` raw/scribble path**: live ink is rendered by the e-ink controller, not by Android's view drawing.

## What it does

Top to bottom:

1. **Scratchpad | Notebooks**: top-level nav, always visible.
2. **Pen / Eraser**: drawing toolbar.
3. Drawing area: a continuous vertical scroll of pages separated by a dashed line. Finger scrolls; the stylus draws.
   - **Scratchpad**: blank pages top to bottom, always ending with a blank page. Opens on that last page.
   - **Notebooks**: a row of notebook tabs (seeded with **comedy**, **journal**, **actions.life**, **miscellaneous**), then that notebook's pages in order. Five tabs fit; more scroll sideways. **+** creates a notebook. **⋯** (or long-press a tab) renames or deletes the open notebook. Deleting asks first, and the notebook's pages move back to the end of the Scratchpad. No page is deleted.
4. Bottom-right of every page: **Move** and **Wipe**. Move opens a menu of notebooks plus **+ New notebook**. Tapping one appends the page as the newest page of that notebook. Wipe clears that page only. Both are disabled on blank pages.

Live ink uses `TouchHelper.create` → `openRawDrawing` → `setRawDrawingEnabled(true)` on one `SurfaceView` under the page stack, with hardware render on. Live stroke style is **`TouchHelper.STROKE_STYLE_FOUNTAIN`**, base width **0.50mm** (Notes default) via `TypedValue.COMPLEX_UNIT_MM`. TouchHelper has no public setter for Notes pressure 30% or stroke stabilization 60%, so those stay firmware-default.

Completed strokes freeze to a per-page bitmap on pen-up. Eraser deletes whole strokes. Raw drawing pauses while scrolling or while a menu or form is open. Menus and forms are drawn inside the window because separate popup windows do not show over the TouchHelper surface on Boox.

## Storage and sync

Local-first, as designed in the project's storage design doc:

- **SQLite (Room)** is the source of truth on the device. Every pen-up writes one row plus one ink file before returning, and never waits on the network.
- **Ink files**: one compact binary stroke file per board in `files/ink/<board-id>.zdi`, with a deflated body of float32 points. Rows keep only the sha256 and the size.
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

## Web overview (`web/`)

A read-mostly view of the whole library for a big monitor: a notebooks sidebar, a dense thumbnail grid with a column slider (2–16 columns, or the `−` / `=` keys), and a full-size viewer (click a page; arrow keys flip, Esc closes). There is no drawing. You can drag to reorder pages, drag a page onto a notebook in the sidebar (or use its ⋯ menu) to move it, and rename notebooks. Edits go through the server as ordinary synced changes (a fractional `position` / `notebook_id` or `title`, a fresh `rev`, a newer `updated_at`), so the Boox picks them up on its next pull.

Next.js 16 + TypeScript + Tailwind + shadcn/ui. Sign in with the device token (the same `DEVICE_TOKENS` value the Boox uses). The token is kept in an httpOnly cookie, and the Next server proxies `/api/zd/*` to the sync server's `/web/*`, so the browser never talks to the sync server directly.

Run everything with Docker and load the demo library, which is fake notebooks and pages so you do not need a Boox:

```bash
cd server
docker compose up -d --build --wait                        # Postgres + MinIO + sync :8787 + web :43917
docker compose run --rm sync node dist/scripts/seed.js     # optional: demo notebooks and pages
open http://localhost:43917                                # device token: dev-device-token
```

For a wall display on a trusted network, `ZD_DEVICE_TOKEN=dev-device-token docker compose up -d` skips the sign-in screen.

Dev mode, against a running sync server:

```bash
cd web && npm ci
ZD_SERVER_URL=http://127.0.0.1:8787 npm run dev            # http://localhost:43917
npm test && npm run lint && npm run typecheck
```

**Thumbnails.** The server renders PNGs from the stored ink with the same width model as `InkRenderer` (pressure, end taper, 0.50 mm at 300 PPI). It reads `.zdi` versions 1 and 2. Rendering starts in the background after every push. `thumb/<ink_hash>.png` (480 px wide) and `render/<ink_hash>.png` (1264 px, the device width) go in the same bucket as the ink. Missing images are also rendered on first request. Images are keyed by ink hash rather than written into `boards.thumb_hash`, so they can never go stale and the server never writes a column the device also writes. The app is unchanged. The page size is not in the ink file, so the server assumes the Go 7 portrait page (`PAGE_WIDTH` / `PAGE_HEIGHT`). Ink beyond that grows the page at the same aspect instead of being cropped. To render thumbnails for ink synced before this existed:

```bash
docker compose run --rm sync node dist/scripts/backfill-thumbs.js
```

Web endpoints (bearer device token, no schema header):

| Endpoint | |
| --- | --- |
| `GET /web/notebooks` | Scratchpad first (`id: "scratchpad"`), then notebooks by `position`, with page counts |
| `GET /web/notebooks/:id/pages` | Live pages in `position` order with `thumb_url` / `render_url` (null for blank pages) |
| `GET /web/thumbs/<ink_hash>.png`, `GET /web/renders/<ink_hash>.png` | PNGs, immutable cache headers |
| `POST /web/pages/:id/move` | `{"notebook_id": "<id>" \| "scratchpad", "after_id": "<page id>" \| null}`. Omitting `after_id` appends, `null` puts the page first. Only the moved row changes. |
| `PATCH /web/notebooks/:id` | `{"title": "…"}` |

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
- `InkRenderer` — pressure + end-taper freeze strokes and eraser hit-tests.
- `data/BoardRepository` — what the UI calls: list scratchpad and notebook pages, save ink, move, wipe, and create / rename / delete notebooks. `RoomBoardRepository` implements it with Room, ink files and the Documents mirror.
- `data/LegacyBoardImporter` — one-time import of the old `zetteldraw-boards.json`.
- `sync/` — `SyncEngine` (push the outbox, then pull until caught up), `SyncClient` (HTTP), `SyncWorker` / `SyncScheduler` (WorkManager), `SyncConfig`.
- `server/` — sync service, shared SQL migrations, thumbnail rendering, web endpoints, docker-compose.
- `web/` — Next.js overview of all notebooks and pages.
