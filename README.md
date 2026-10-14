# Zetteldraw

Handwriting-first notes on Boox. Proven on a **Go 7 Color II**: a third-party app can match Notes-like stylus latency via the **Onyx Pen SDK `TouchHelper` raw/scribble path** (live ink by the e-ink controller, not Android `View` drawing).

Tip APK: [https://zetteldraw.com/apk](https://zetteldraw.com/apk). **Product behavior (UI + sync, regression checklist):** [docs/product-behavior.md](docs/product-behavior.md). Full correctness argument: [docs/CORRECTNESS.md](docs/CORRECTNESS.md).

**Greenfield rewrite pack** → [docs/greenfield/](docs/greenfield/) — condensed product/architecture/sync specs + screenshots for a **from-scratch** rewrite. Tip v86 is reference behavior only; prefer greenfield over continued in-place refactor of the legacy tree.

---

## The only three irreversible bugs

UI bugs are recoverable. These three are not — they permanently lose or corrupt notes. Everything we ship for data safety is aimed at them.

| # | Irreversible bug | Why it matters |
| --- | --- | --- |
| **1** | **Corrupted data** | Wrong-page strokes, torn logs, half-applied merges: the ink the user drew is gone or lies. |
| **2** | **Lost data from pages** | Move / reorder / tear that drops a page or files it into the wrong notebook so it looks gone. |
| **3** | **Lost data from bad sync / restore** | Empty seeds clobbering cloud, two writers racing, Play cutover without a complete backup. |

### Core guarantees (enforced)

**Single device, Confirm enabled (Move / ink):**

- Strokes bind to the viewport at pen-down (`InkViewport` + navigation fence). Scroll/tab cannot rebind them.
- One write-behind writer: UI never waits on encode/`fsync`; pull skips unflushed edits; push sends only ink on disk.
- Move resolves by **page id**; cross-notebook **dest disk before source tear**; `holdFlush` blocks queued `saveInk` and sync **pull** mid-tear (v46 / v61 / v62).
- Move destination is the **open collection**, not the midline page’s sheet; Confirm disabled while lists load; stale parent midline → append on dest (v63).

**Google account + sync (v64):**

- **One live writer** per account. Second Boox gets `403 not_live_writer` on push until **Prepare transfer** (live Boox) → web **Hand off** + claim.
- Hand off refused until `/web/backup-status` ready **and** Prepare transfer (or lost-device confirm).
- Never-synced device: **pull before push**; untouched seeds (`updated_at === 0`) are not queued first and cannot clobber server notebooks.
- `markSynced` only when outbox is empty. Missing S3 ink fails the pull clearly (cursor does not skip past it).

### Explicit non-guarantees

- **Multi-device concurrent edit of one notebook** without Hand off: whole-log LWW (loser → hidden conflict copy), not stroke CRDT merge.
- **Kill** after dest rewrite / before source flush (brief duplicate), or in the write-behind window (newest unflushed snapshot may be lost).
- **Web titles/thumbs ≠ restore-safe.** Postgres metadata and cached PNGs do not prove `notebook_logs` are in S3.
- Partial outbox + uninstall; missing S3 objects; Documents mirror is not auto-imported after uninstall.
- Intentional Wipe / Delete / Lasso Cut (by design). Pre-v63 wrong-dest Moves already synced.

Tests that lock the guarantees (names encode the claim):  
`InkNavigationTest.*`, `WriteBehindTest.aPullNeverOverwritesAnEditStillBeingWritten`, `PageMoveSafetyTest.staleSliceIndexStillMovesTheRequestedPageNotItsNeighbor`, `PageMoveScratchpadToSubNotebookTest.syncPullDuringHeldCrossNotebookMoveCannotDropPage`, `…placeIntoHomePageChildIgnoresStaleParentMidlineTarget`, `EnsureUnsyncedQueuedTest.markSyncedOnlyWhenOutboxEmpty`, server `writer.test.ts` / `backup-status.test.ts` / `lww.test.ts` (`rejects untouched seed placeholders…`). Details and concurrency model: [docs/CORRECTNESS.md](docs/CORRECTNESS.md).

### Scale (honest limits — not race proofs)

Race invariants assume the process stays alive. They do **not** mean an unbounded Scratchpad fits in ~256 MB heap.

| Envelope | Expectation on tip **v69+** |
| --- | --- |
| **~100 pages** / light–moderate ink (log ≲ few MB) | **Safe** for open, draw, lasso, Move, sync/Prepare |
| **~1 000 pages** or fat log (~10–20 MB; prod Scratchpad ~14 MB) | UI **degrades** (O(n) open/lasso/erase). **Prepare/push heap OK** — v69 streams blobs >256 KiB via `PUT /sync/blob` (fixes ≤v68 ~75 MB Base64 OOM). Drain time still ∝ whole-log upload. |
| **~10 000 pages** / huge log | **Fail risk** — open/replay, flush re-read, and pull `byte[]` can still OOM; not a watertight “sync always works” claim |

Remaining gap: streaming **decode/open/pull-apply**, not the Prepare Base64 path. Full table: [docs/CORRECTNESS.md § Scale](docs/CORRECTNESS.md#scale-pages--strokes--heap--not-a-race-proof).

---

## What it does

Top to bottom:

1. **Top bar**: `[sheet icon] | comedy  journal  actions.life  miscellaneous … +`. The Scratchpad is pinned left, outside the tree. Only top-level notebooks sit on this bar. **+** creates a top-level notebook and scrolls with the tabs. The open item gets a 2 dp black underline (and a bold label); nothing is filled. A selected notebook that has children opens another bar, directly under this one, listing only those children. Deeper selection adds more bars. Ancestor tabs stay selected.
   - Hold a notebook tab to rename, place it under another notebook, make it top-level, or delete it. The menu hangs under that tab (and opens the notebook). Delete sends that notebook's own pages back to the Scratchpad and promotes its children to its parent. **+** on a bar creates a notebook in that bar. Hold the Scratchpad icon to read the launch timeline (the same text as `files/launch-log.txt`) in a panel inside the window.
   - Design choice: no ⋯ in the bar, so it stays calm; anchoring every menu and form to the tab (or page) it acts on keeps it unambiguous what it affects.
2. **Undo · Redo | Pen · Eraser · Lasso**: icon toolbar (Lucide icons as vector drawables, black on white). The active tool has the same 2 dp black underline; Undo / Redo fade when there is nothing to do. A hint line on the left says what the Lasso is doing. Tool chrome, `PageInkView` tool enum, and the TouchHelper brush share one `ToolController.setTool` path (v53/v59) so the underline cannot stick on Pen while the stylus draws a lasso dash. Root overlays never cover the tool row; after a lasso move the tool stays **Lasso** (Notes).
   - Style: controls are borderless (toolbar icons, +, Move, the page ⋯, menu rows), with tap targets of at least 48 dp. A menu or form is one thin frame with plain rows inside, never a box inside a box.
   - A stroke is points on the notebook's paper. It has an id and no page. Pages are slices of that paper. The dashed line is a mark at a slice edge, not a gap, and it does not cut a stroke. Pen-up appends one stroke however many slices it crosses. The lasso hit-tests in paper coordinates, including across the mark, and a drag adds one (dx, dy). Undo subtracts it. The eraser deletes whole strokes by id. The only cut is tear: Delete, Wipe, and Move.
   - Undo / redo covers pen strokes, eraser passes and lasso moves. On the paper, undo appends the inverse record (delete the stroke, append it again, or subtract the drag). The stack holds 50 steps for the open notebook and is cleared when another list opens, a pull replaces the log, or the app restarts.
   - Wipe and Delete page are not in the history: they already confirm first, and undoing a Delete would need un-deleting a synced tombstone.
3. Drawing area: a continuous vertical scroll of pages separated by a dashed line. Finger scrolls; the stylus draws.
   - The Scratchpad and every notebook are the same kind of page list: pages in order, always ending with one blank page. A parent does not show its children's pages. The first stroke on that blank stores it (and syncs it) at the next position in that list, and a new blank appears after it. An empty notebook shows one blank page to draw on. The **first** open of a collection jumps to its last page (Scratchpad: trailing blank; notebook: last inked page, or the first page when none have ink). After that, leaving and coming back restores the scroll left behind (in-memory for the session, and in SharedPreferences across process death). An explicit scroll (a page link) still wins for that navigation.
   - A page is as tall as the drawing area under the two bars (minus the gap), so one whole page and its controls fit on screen. On the Go 7 Color II (1264×1680, xhdpi, full screen) that is **1264×1420**. Pages from before v10 whose ink reaches below that keep their old full-window height (1264×1680 there), so no ink is cropped or shifted.
4. Top left of every page: its number as **k/n** (n counts the blank page at the end), small and grey, with no ink under it. Bottom left: link refs, then **Link** and **Reorder** (word-sized). Bottom right: **Move** and **⋮**. **Link** and **Reorder** share the navigate-to-target family: each opens a floating bottom snackbar; navigate with normal bars/scroll; Confirm enables on a valid target. Link stores two page ids. Reorder places the source **before** the page under the viewport midline (`Place … before …` / `Place … in …` on a trailing blank) — same-notebook or cross-notebook; dense pages stay atomic; undo is not offered for page place. Snackbar page refs are current-only (no `/total`); top-left chrome stays `k/n`. **Move** opens a **notebook hierarchy menu** (not snackbar+nav) so Scratchpad pages can be filed without visiting the dest; pick a notebook (or **+ New notebook**) and the page appends as newest via place-before trailing blank (page id + holdFlush / dest-id safety kept). **Place under…** on a notebook tab uses that same shared hierarchy menu to pick a parent (self/descendants omitted). **⋮** opens Wipe / Delete page. Blank pages grey Link/Reorder/Move; trailing blank greys ⋮.

**Lasso** (select and move ink on the notebook paper):

- Tap **Lasso**, then circle ink. The firmware draws the outline dashed, with no lag. A stroke is selected when at least half its points are inside; a dot when its point is inside. Hit-testing thins the outline for speed and only rebuilds the page slices that are on screen, so a dense page does not freeze after lift.
- The selection gets a dashed box, and raw drawing pauses. Drag the box with the pen or a finger. During the drag only a moved bitmap is repainted, in fast monochrome mode (the page bases from select are reused; the dense page is not re-rendered each frame). On one-sheet notebooks the box can cross the dashed mark; the drag is one shift. Holding near the top or bottom of the drawing area edge-scrolls the sheet so ink can be carried onto other pages without fighting Onyx raw drawing (pen stays paused for the selection).
- A compact floating pill above the dashed selection offers **Cut · Copy · Delete** (optional **Paste** if the clipboard is already full). After Copy or Cut, scroll notebooks normally, then **long-press** on a page for **Paste** (at the hold point) or **Clear clipboard**. No edge-scroll while dragging a selection — transport is Copy/Paste.
- On release the strokes move first, then one refresh shows them at the destination (no flash back to the origin). Only slices that met the move are invalidated. The notebook log appends one translate record, the tool stays on **Lasso**, and raw drawing resumes after 500 ms.
- Tap outside the box to cancel. **Undo** puts a lasso move back; **Redo** moves it again.

Live ink uses `TouchHelper.create` → `openRawDrawing` → `setRawDrawingEnabled(true)` on one `SurfaceView` under the page stack, with hardware render on. Live stroke style is **`TouchHelper.STROKE_STYLE_FOUNTAIN`**, base width **0.50mm** (Notes default) via `TypedValue.COMPLEX_UNIT_MM`, **black only** (pen color menu / Kaleido Color CU path removed). TouchHelper has no public setter for Notes pressure 30% or stroke stabilization 60%, so those stay firmware-default. Paper pen-up paints the new stroke into cached slice bitmaps instead of rebuilding the whole sheet.

Completed strokes freeze to a per-page bitmap on pen-up. Eraser deletes whole strokes. Raw drawing pauses while scrolling or while a menu or form is open. Menus and forms are drawn inside the window because separate popup windows do not show over the TouchHelper surface on Boox.

## Storage and sync

Local-first. Day to day the live Boox is the source of truth; cloud is backup + restore seed. See [docs/CORRECTNESS.md](docs/CORRECTNESS.md) for the irreversible-bug argument.

- **SQLite (Room)** holds the library on device. A pen-up appends one stroke to the notebook's in-memory log and returns. One writer thread appends only that tail to `files/ink/<notebook-id>.zdl` and then updates the log row. It does not rewrite the notebook on every pen-up. The UI thread never waits on ink writes, sync or the network.
- **Strokes and navigation**: the Onyx SDK posts pen callbacks to the main thread, so each stroke is converted with the page layout and scroll that were on screen when it was drawn (`InkViewport`), and scrolls or page-list changes apply only after strokes already queued. Debug builds log main-thread I/O (StrictMode) and stalls (`adb logcat -s zd-stall zd-repo`).
- **Ink files**: the notebook log is `files/ink/<notebook-id>.zdl` (magic `ZDL`). The pre-migration page files `files/ink/<board-id>.zdi` are left in place so installing v19 over v20 still opens the ink that existed before the switch. v19 does not read `.zdl`, so strokes created after migration are visible only to v20. Format version 2 `.zdi` stores a stable id per stroke (layout in `data/InkCodec`). The log's layout is `data/NotebookPaper`.
- Tables `notebooks`, `boards`, `notebook_logs`, and `page_links` match Postgres column for column (`server/migrations/`). A page link is the two page ids. It syncs as its own row and copies no ink. Deleting either page tombstones the link. A unit test fails if they drift. `notebook_logs` is one append-only ink log per notebook. Two devices editing one notebook without Hand off are editing that one object under whole-log LWW: the loser is shelved as a hidden conflict copy (ink not silently deleted; not a merged CRDT). A pull replaces the log and cannot leave half a stroke. The old per-page blobs stay. Device-only tables: `outbox` (rows still to push) and `sync_state` (the pull cursor).
- **Google accounts (v64):** one **live writer** per account. `POST /sync/push` is gated by `assertCanPush`; cutover uses web Settings → **Hand off** → new device `POST /sync/claim-writer`. Never-synced devices **pull before push**; untouched seeds are not uploaded until after restore.
- Ids are client UUIDs. Order uses fractional `position` keys (`a0`, `a0V`, …), so appending as newest or moving a page touches one row.
- Deletes are tombstones (`deleted_at`). Conflicts are last-write-wins per row. A losing board with different ink is kept as a hidden conflict copy (`conflict_of`), so ink is never dropped.
- **Backup without a server**: ink files plus `index.json` are mirrored to `Documents/zetteldraw/`, which survives an uninstall (not auto-imported after reinstall).
- **First launch migration**: an existing `files/zetteldraw-boards.json` (v4/v5) is imported with the same board ids and order. It is then renamed to `zetteldraw-boards.json.migrated` and kept as a fallback.

**Sync is off until a server URL is set.** Build with one:

```bash
./gradlew :app:assembleDebug -Pzetteldraw.syncUrl=http://<mac-lan-ip>:8787 -Pzetteldraw.syncToken=dev-device-token
```

With a URL set, WorkManager syncs every 15 minutes when the network is up, and again each time the app goes to the background. Failures retry with backoff. `SyncConfig.save()` can set the URL at runtime for a future settings screen.

## Sync server (`server/`)

TypeScript on Node 22 (Fastify, `pg`, AWS S3 client). Metadata goes in Postgres; ink blobs go in S3-compatible storage (MinIO locally) at `ink/<sha256>`. Auth is Google (or a legacy device token until claim). Push for Google users requires the live-writer lease. See [server/README.md](server/README.md) for the protocol.

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

A read-only companion for a big monitor: notebooks sidebar, dense thumbnail grid (column slider / `−` / `=`), full-size viewer (click a page; arrows flip, Esc closes). **No drawing and no library edits from the browser** — rename/move/reorder are refused. The intentional web writes are the lease ceremony: Settings → **Move to a new device** → **Hand off** / Cancel / lost-device confirm (Hand off blocked until the live Boox **Prepare transfer** or lost-device ack, and backup-status is ready). Notebook titles and thumbs are not a hydrate proof; use the backup banner before Play cutover.

Next.js 16 + TypeScript + Tailwind + shadcn/ui. Sign in with Google (or a local device token for demos). The token is kept in an httpOnly cookie, and the Next server proxies `/api/zd/*` to the sync server's `/web/*`, so the browser never talks to the sync server directly.

Run everything with Docker and load the demo library, which is fake notebooks and pages so you do not need a Boox:

```bash
cd server
docker compose up -d --build --wait                        # Postgres + MinIO + sync :8787 + web :43917
docker compose run --rm sync node dist/scripts/seed.js     # optional: demo notebooks and pages
open http://localhost:43917                                # device token: dev-device-token
```

Set `ZD_DEVICE_TOKEN` on the web service to drop sign-in entirely: the Next server uses that token for every call, and anyone who can open the site sees and can reorder the library. The hosted site at zetteldraw.com runs this way. Locally: `ZD_DEVICE_TOKEN=dev-device-token docker compose up -d`.

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
| `GET /web/backup-status` | Ready only when every owned board + `notebook_log` ink blob exists in S3 |
| `GET /web/writer`, `POST /web/writer/hand-off`, `POST /web/writer/lost-device`, `POST /web/writer/cancel` | Live-writer status and Hand off ceremony (Hand off requires Prepare transfer or lost-device ack + backup ready) |
| `POST /sync/prepare-transfer` | Live Boox final-sync ack; unlocks web Hand off |

## Build

Requires JDK 17+, Android SDK platform 35, and network access to Onyx's Maven repo (HTTP):

`http://repo.boox.com/repository/maven-public/`

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug
```

Play App Bundle (unsigned unless `keystore.properties` or `ZETTELDRAW_STORE_*` is set; release builds do not embed `zetteldraw.syncToken`):

```bash
./gradlew :app:bundleRelease \
  -Pzetteldraw.syncUrl=https://zetteldraw-sync.onrender.com \
  -Pzetteldraw.googleWebClientId=YOUR_WEB_CLIENT_ID.apps.googleusercontent.com
```

The hosted sync URL is the one in `render.yaml`. Signing, the upload keystore, and the Play Console checklist are in [docs/play-store/README.md](docs/play-store/README.md).

APK:

`app/build/outputs/apk/debug/app-debug.apk`

`applicationId` is `com.zetteldraw.penpoc`, the same id as existing sideloads. A Play build is signed with the upload key, then by Play App Signing, not with `app/debug.keystore`, so it will not install over a debug-signed sideload until that install is removed.

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

4. Open **Zetteldraw**. You land on the last blank Scratchpad page. Draw with the stylus; finger-scroll through pages. **Move** opens a bottom snackbar; navigate with the notebook bars and scroll, then Confirm places the page before the midline target (or appends on a trailing blank) in the **open** collection. A dense page Moves in one step: either both notebooks update, or neither does — ink is not dropped mid-move. **Eraser** deletes strokes; a page's **⋯** wipes or deletes it.

If the screen stays white and nothing appears under the pen:

- Confirm this is a stylus-capable Go 7 Color II firmware, not a finger-only Go Color 7 Gen 1.
- Check `adb logcat | grep -i -e TouchHelper -e onyx -e zetteldraw -e penpoc`.
- Hidden API exemption is already applied in `PenApp`. Do not remove it; TouchHelper needs it on Android 13.

## Remaining device step

None for a normal Gradle build: the Pen SDK is pulled from `repo.boox.com` at compile time.

If that Maven host is blocked, download the AAR yourself and drop it in `app/libs/`:

`http://repo.boox.com/repository/maven-public/com/onyx/android/sdk/onyxsdk-pen/1.5.5/onyxsdk-pen-1.5.5.aar`

Then point `implementation` at `files("libs/onyxsdk-pen-1.5.5.aar")` plus the transitive `onyxsdk-device` / `onyxsdk-base` artifacts from the same repo. The Java wiring in `CanvasActivity` does not change.

## Layout

- `PenApp` — Hidden API bypass required by Onyx on Android 11+.
- `CanvasActivity` — the one top bar (Scratchpad, notebook tabs with their anchored menu, +), the linking line under those bars, Undo / Redo / Pen / Eraser / Lasso icon toolbar, page slots with page number, link lines, Link, Move and ⋯ (Wipe / Delete).
- `PageScroller` — finger scroll over the page stack; forwards stylus gestures to the ink surface, and every touch while a lasso selection is up.
- `PageInkView` — `SurfaceView` + `TouchHelper` live ink; paints the visible pages at the current scroll offset. Owns raw-drawing pause/resume, and the lasso selection, drag preview and commit.
- `Lasso` — lasso hit-test, offset clamping, move (rewrites points, keeps ids).
- `InkHistory` — undo / redo stack of stroke edits by id (add, remove, move), capped at 50.
- `InkRenderer` — pressure + end-taper freeze strokes and eraser hit-tests.
- `data/BoardRepository` — what the UI calls: one `pages(notebookId)` list for the Scratchpad (`null`) and each notebook, always ending with a blank page; save ink, move, wipe, delete pages, and create / rename / delete notebooks. `RoomBoardRepository` implements it with Room, ink files and the Documents mirror.
- `data/LegacyBoardImporter` — one-time import of the old `zetteldraw-boards.json`.
- `sync/` — `SyncEngine` (on a never-synced device: pull, claim live writer, then push; otherwise claim → push → pull), `SyncClient` (HTTP, including `/sync/claim-writer`), `SyncWorker` / `SyncScheduler` (WorkManager), `SyncConfig`.
- `server/` — sync service, shared SQL migrations, live-writer lease, thumbnail rendering, web endpoints, docker-compose.
- `web/` — Next.js read-only overview + Hand off Settings ceremony.
- `docs/CORRECTNESS.md` — invariants, concurrency model, tests, non-guarantees, and **Scale** (heap / sync payload / OOM envelope) for the three irreversible bugs.
- `docs/greenfield/` — **Greenfield rewrite pack** (product behavior, architecture, sync model, non-goals, key screenshots).
