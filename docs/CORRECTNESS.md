# Correctness: the three irreversible bugs

UI glitches, wrong underlines, and awkward snackbars are recoverable. These three are not. This document states what the code **actually enforces**, which tests lock those claims, and what remains **unguaranteed**. Tip app versions referenced: **v63** (Move dest), **v64** (Hand off / live writer), **v66** (Prepare transfer gate), **v67** (Sign-In error/Prepare reasons), **v68** (Prepare transfer on-device only; crash-on-retry + blank `/` redirect fixed). **Scale** below is honesty about heap and payload size — not a race proof. Tip `main` after v68 still materializes whole notebook logs for push/Prepare; streaming large logs is a known gap (in flight separately).

For the short version, see the [README](../README.md#the-only-three-irreversible-bugs).

---

## Why only these three

| Class | Why irreversible |
| --- | --- |
| **1. Corrupted data** | A stroke bound to the wrong page, a torn log, or a half-applied LWW merge cannot be “undone” by the user. The ink they thought they drew is gone or lies in the wrong place. |
| **2. Lost data from pages** | Move / reorder / tear that drops a page (or files it into the wrong notebook so it looks gone) permanently removes that slice from where the user expects it. Intentional Wipe / Delete / Lasso Cut are out of scope — those ask first. |
| **3. Lost data from bad sync / restore** | Empty seeds overwriting cloud, a second Boox pushing while another is live, or Play cutover without a complete S3 backup permanently lose or clobber the library. Web titles/thumbs alone do not prove ink is safe. |

Everything else (chrome, sounds, scroll memory, tool underline) can be fixed in a later build without losing notes.

---

## Concurrency model (what races against what)

Three actors share one device:

| Actor | Thread / job | Writes |
| --- | --- | --- |
| **UI** | Main thread | Viewport, page list, Confirm → queues `saveInk` / `placePageBefore` |
| **Async writer** | Single writer executor in `RoomBoardRepository` | Ink file rename + Room txn; dest rewrite then source flush on Move |
| **WorkManager** | `SyncWorker` | Push from disk/outbox, then pull (`applyPull`) |

Proven impossible (single device, under the locks and fences below):

- A stroke converted with a later scroll/tab viewport than the one it was drawn on (`InkViewport` + navigation fence).
- A cross-notebook Move whose dest is rewritten by pull while `holdFlush` is set.
- A pull that overwrites a page whose local snapshot is still unflushed.
- A push that uploads ink bytes that are not yet on disk.
- A second Google-signed Boox successfully `POST /sync/push` while another device holds the live-writer lease (server `403 not_live_writer`).
- Untouched seed notebooks (`updated_at === 0`) clobbering an existing server notebook row.
- Hand off opening while `/web/backup-status` is not ready.
- Hand off opening without live-Boox **Prepare transfer** (or web lost-device confirm).

Not in that list → not claimed. See [Non-guarantees](#non-guarantees).

---

## Bug 1 — Corrupted data (ink correctness)

### Invariants enforced

1. **Stroke ↔ picture at pen-down.** Pen callbacks are ordered on the main thread (Onyx `SFTouchRender` posts with ordinary messages). A scroll/tab change pauses raw drawing, then applies the new `InkViewport` in a later message. Every stroke already queued runs against the old picture.
2. **Bind once.** At conversion, a stroke is attached to the page under its first point and stored in that page’s coordinates. Scroll never rewrites stored points.
3. **Write-behind completeness.** One writer; staged file + `fsync` + atomic rename; under the lock the writer re-checks the snapshot is still newest and the page still live. Deleted/emptied pages are not resurrected by a stuck write.
4. **Sync vs in-flight edit.** Pull skips pages with an unwritten local snapshot. Push sends only ink that is on disk and matches its row.
5. **Whole-log LWW.** A notebook’s ink log is one object. Conflict shelves the other log whole as a hidden copy — never splits a stroke mid-log.

### Tests that lock this

| Test | Claim |
| --- | --- |
| `InkNavigationTest.aStrokeQueuedBeforeAScrollKeepsTheScrollItWasDrawnAt` | Fence before scroll |
| `InkNavigationTest.strokesQueuedDuringAStallIgnoreTheFlingThatFollows` | Fence under backlog |
| `InkNavigationTest.aStrokeQueuedBeforeATabSwitchStaysOnItsOwnPage` | No notebook leak |
| `InkNavigationTest.scrollingWhileASaveIsStuckOnDiskKeepsEveryStrokeInPlace` | UI ≠ disk stall |
| `WriteBehindTest.saveReturnsAndTheUiKeepsWorkingWhileTheDiskIsStuck` | UI never waits on encode/fsync |
| `WriteBehindTest.undoToBlankWhileTheWriteIsStuckLeavesNoInkBehind` | Stuck write ≠ resurrection |
| `WriteBehindTest.deletingAPageWhileItsWriteIsStuckKeepsItDeleted` | Same for delete |
| `WriteBehindTest.pushOnlySendsInkThatIsOnDisk` | Push ⊆ disk |
| `WriteBehindTest.aPullNeverOverwritesAnEditStillBeingWritten` | Pull skips dirty |
| `ConflictTest.newerRemoteEditWinsAndLocalInkBecomesHiddenCopy` | LWW keeps loser ink |
| `server/test/unit/lww.test.ts` — `shelves the other log whole and does not split it` | Log atomicity |

### Kill / crash windows (honest)

Write-behind means the newest snapshot per page can be lost if the process dies before the writer commits. Normally that window is milliseconds; on a stalled disk it grows to the stall. A crash between file rename and the Room row is adopted on the next push (file wins). **These are not “corruption-free under kill”; they are bounded.**

---

## Bug 2 — Lost data from pages (Move / reorder / tear)

### Invariants enforced (single device, Confirm enabled)

| Invariant | Since |
| --- | --- |
| Resolve the slice by **page id**, never a cached `sliceIndex` | v46 |
| Cross-notebook: **dest disk rewrite before source tear**; `holdFlush` vs queued `saveInk` | v46 / v61 |
| Sync **pull** cannot replace a sheet while `holdFlush` | v62 |
| Destination is the **open collection** (`collectionId`), not the midline page’s sheet | **v63** |
| Confirm **disabled while notebook list is loading** (stale slots) | **v63** |
| Stale midline page from another notebook → **append on dest**, never misfile to that page’s sheet | **v63** |
| `placePageBefore` returns false on refuse; Confirm stays in Move mode | **v63** |
| Dense Move is atomic onto dest, or rolls back | earlier + `MovePageTearTest` |
| Post-persist invariant log if page missing on dest or still on source | **v63** |

UI → Confirm → saveInk queue → place (tear + hold under lock) → open dest.  
Writer → dest rewrite, then source flush.  
WorkManager → push (disk), then pull (skips `holdFlush` sheets).

### Tests that lock this

| Test | Claim |
| --- | --- |
| `PageMoveSafetyTest.staleSliceIndexStillMovesTheRequestedPageNotItsNeighbor` | Id, not index |
| `PageMoveSafetyTest.crossNotebookPlaceBeforeRemovesSourceOnlyAfterDestHasPage` | Dest-before-source |
| `PageMoveSafetyTest.sameNotebookPlaceBeforeKeepsEveryStrokeIdAndPageCount` | No drop on reorder |
| `PageMoveScratchpadToSubNotebookTest.lastScratchpadPageIntoActionsLifeSubNotebookKeepsPageAndInk` | Nested dest |
| `PageMoveScratchpadToSubNotebookTest.saveInkFlushBeforeDestPersistCannotDropLastScratchpadPage` | holdFlush vs saveInk |
| `PageMoveScratchpadToSubNotebookTest.syncPullDuringHeldCrossNotebookMoveCannotDropPage` | holdFlush vs pull |
| `PageMoveScratchpadToSubNotebookTest.placeIntoHomePageChildIgnoresStaleParentMidlineTarget` | Explicit dest notebook |
| `MovePageTearTest.densePageMovesAtomicallyOntoDoodles` | Dense atomicity |
| `MovePageTearTest.appendMovedFailureRollsBackDestinationAndTearMoveRestoresSource` | Failure → restore source |

---

## Bug 3 — Lost data from bad sync / restore

### Chosen model (v64)

**One live Boox writes. Cloud backs up. Hand off before a new device can upload.**

| Mechanism | What it prevents |
| --- | --- |
| Live-writer lease + `POST /sync/claim-writer` | Two Google devices both pushing |
| Boox **Prepare transfer** then web **Hand off** (48h window) | Old device keeps writing after cutover starts; web Hand off without device prepare |
| Hand off refused until prepare/lost-device + `backup-status` ready | Cutover without every board + `notebook_log` ink in S3 |
| First sync **pull before push** | Empty Room + seed notebooks uploading first |
| Seeds not queued until after restore pull; server rejects `updated_at === 0` clobber | Seed ids wiping a real cloud notebook |
| `markSynced` only when outbox empty | “Caught up” lie while uploads pending |
| Pull errors on missing S3 ink (cursor does not advance past it) | Silent hydrate with holes |

Safe Play cutover requires **all of**: Boox **Prepare transfer** under Google G, web banner **backup ready**, web **Hand off**, Play build same `SYNC_URL`, sign-in as G, then open ink on the new device before discarding Documents.

**Web list/thumbs alone are not a hydrate proof** — they can reflect Postgres metadata and cached PNGs while a `notebook_log` blob is missing.

### Tests that lock this

| Test | Claim |
| --- | --- |
| `server/test/unit/writer.test.ts` — `blocks Hand off until Prepare transfer` | Device prepare gate |
| `server/test/unit/writer.test.ts` — `blocks Hand off until backup is ready even after Prepare transfer` | Backup gate |
| `server/test/unit/writer.test.ts` — `Prepare transfer unlocks Hand off; new device can claim` | Ceremony |
| `server/test/unit/writer.test.ts` — `lost-device ack unlocks Hand off without Prepare transfer` | Secondary path |
| `server/test/unit/writer.test.ts` — `assertCanPush rejects a second device while live` | Single writer |
| `server/test/unit/backup-status.test.ts` — `fails when a notebook_log blob is missing even if boards look fine` | Logs in S3 |
| `server/test/unit/lww.test.ts` — `rejects untouched seed placeholders that would clobber a server notebook` | No seed clobber |
| `EnsureUnsyncedQueuedTest.markSyncedOnlyWhenOutboxEmpty` | Caught-up honesty |
| `NotebookManagementTest.seedsLoseToAnyRealEditFromAnotherDevice` | Seeds lose to real data |
| `ConflictTest.missingBlobStaysQueued` | Missing ink ≠ silent drop |
| Integration roundtrip — loser ink kept as hidden copy | LWW safety net |

---

## Scale (pages / strokes / heap) — not a race proof

The sections above argue **logical** safety under concurrency. They do **not** claim the Boox can hold an unbounded library. Go 7 Color II–class devices have on the order of **~256 MB Java heap**. Correctness under races can still fail as **OOM** (process death mid-sync / mid-Prepare) when a single notebook log is large. That is a hard limit today, not a soft “gets slower.”

**Known incident (tip ≤ v68):** Scratchpad `notebook_logs` in cloud ~**14.3 MB** (2026-10-12). Prepare / sync later hit a ~**75 MB** allocation and OOM’d. That matches tip push math: load whole `.zdl` → Base64 into JSON → `JSONObject.toString()` → `getBytes` → HTTP body, with other copies (open `NotebookPaper.log`, `bytes()`, flush re-read for SHA-256) still live. Streaming large logs is **not** on tip `main` yet (parallel fix in flight after v68); until it lands, treat large-log sync/Prepare as **can fail by OOM**.

Server `bodyLimit` is **64 MB** for `POST /sync/push`. A Base64’d blob is ~4/3 of raw size, so a single log much above ~**40 MB** raw can be refused even if the device survives the alloc.

### What is O(n) today (tip `main`)

| Path | Cost in n | Notes |
| --- | --- | --- |
| Open notebook (`ensureSheet` + `NotebookPaper.replay`) | O(log bytes) + O(strokes) | Whole `.zdl` into RAM; decoded stroke list kept. Opened sheets are **not** evicted on tab switch (only test hook / delete). |
| Pen-up / erase / lasso translate append | O(log bytes) | `append` does `Arrays.copyOf` of the full in-memory log each record. |
| Writer `flushSheet` | O(log bytes) | Copies log, appends suffix, **re-reads entire file** for SHA-256 + mirrors. |
| Dense Move (`appendMoved`) | O(taken strokes + dest log growth) | Atomic batch (v29); still one big dest suffix + rollback copies. Tear scans strokes intersecting the slice. |
| Sync push / Prepare drain | O(log bytes) **per** queued notebook_log | `PUSH_BATCH = 50` rows, but each log blob is the **whole** file inline Base64. One fat Scratchpad log dominates. |
| Sync pull | O(batch) rows; blobs inline Base64 | `PULL_LIMIT = 200`; same whole-blob materialization on apply. |
| Outbox drain time | O(outbox × rounds) | Up to `MAX_ROUNDS = 1000` push/pull rounds per pass; wall time grows with blob size and Render RTT, not just row count. |
| Lasso hit-test | O(strokes × points × ≤64 verts) | Outline thinned (`MAX_HIT_VERTICES = 64`); paper lasso walks **all** strokes on the sheet. |
| Eraser | O(all strokes on sheet) | Hits every stroke against the wipe path. |
| Render / scroll | Bounded bitmaps | `BITMAP_CACHE_SIZE = 3`; visible pages only. Dense **page** rebuild is O(strokes on that page), not whole library. |

### Envelope: ~100 / ~1 000 / ~10 000

Orders of magnitude for **one notebook** (Scratchpad counts). “Strokes” = ink strokes on that sheet, not UI events. These are engineering judgments from code + the incidents above — **not** load-test certificates.

| Scale | Memory / open | Sync payload & Prepare | Hit-test / lasso / erase | Move (page tear) | Render / scroll | Outbox drain |
| --- | --- | --- | --- | --- | --- | --- |
| **~100 pages**, light–moderate ink (log ≲ few MB) | **OK** — normal tip use | **OK** — push peak well under heap | **OK** — select stays responsive with thinning | **OK** — dense page still atomic | **OK** — 3-page bitmap cache | Seconds–minutes depending on network |
| **~1 000 pages** or dense ink (log ~10–20 MB; Scratchpad already ~14 MB in prod) | **Degrades** — full log + stroke list resident; multiple opened notebooks stack | **Fails possible** — Base64 JSON peak can approach/exceed ~75 MB alloc (observed OOM); Prepare = full sync first | **Degrades** — paper lasso/erase O(all strokes); may hitch on lift, not freeze by design | **Degrades** — tear/appendMoved copies grow with page density + log size; still prefer both-intact over drop | **Mostly OK** — still 3 bitmaps; first open / full invalidate of a dense page costs O(page strokes) | **Degrades** — each Scratchpad change re-pushes the **entire** log; drain time ∝ log size × RTT |
| **~10 000 pages** or log ≳ ~40 MB | **Likely fail** — heap + retained sheets | **Fail** — device OOM and/or server **64 MB** body limit | **Degrades → unusable** — O(n) walks on huge stroke lists | **Fail risk** — large intermediate `byte[]` during tear/rewrite | Open/replay alone may OOM before UI settles | May never reach `outbox == 0` if push OOMs or 413 |

**Beyond:** no compaction/GC of notebook logs; append-only `.zdl` only grows. Splitting Scratchpad / archiving pages is an operational mitigation, not a product guarantee.

### Guaranteed vs degrades vs fails (scale)

| Claim | Status |
| --- | --- |
| Race invariants in Bugs 1–3 while the process stays alive | **Guaranteed** (single device / live-writer rules as above) |
| UI never waits on encode/`fsync`/network | **Guaranteed** for the write-behind design — unless the process is killed by OOM |
| Dense Move does not drop ink mid-append (v29) | **Guaranteed** when Move completes without OOM; on OOM mid-rewrite see crash windows |
| Sync / Prepare succeeds for arbitrary library size | **Not guaranteed** — **fails** by OOM or server body limit on large whole-log pushes |
| Lasso/erase stay interactive at 10k strokes | **Not guaranteed** — **degrades** (O(n)); thinning helps select, does not cap sheet size |
| Render stays O(visible pages) | **Mostly guaranteed** — cache size 3; opening the sheet still pays full replay |
| Streaming / chunked ink upload | **Gap** on tip after v68 — do not claim watertight sync at Scratchpad-scale until that lands |

---

## Non-guarantees

State these out loud; do not paper over them.

| Non-guarantee | Why |
| --- | --- |
| **Multi-device concurrent editing of one notebook without Hand off** | Whole-log LWW; one log wins, the other is a hidden conflict copy. Not CRDT merge of strokes. |
| **Kill after dest rewrite, before source flush** | Brief duplicate on disk possible; not silent drop, but not “exactly once” either. |
| **Crash in the write-behind window** | Newest unflushed snapshot per page can be lost. |
| **Unbounded library / large notebook_log sync** | Whole-log Base64 push; Boox ~256 MB heap; observed ~75 MB Prepare OOM; server body limit 64 MB. Streaming not on tip yet. |
| **Web titles / thumbs ⇒ restore-safe** | Metadata and cached PNGs ≠ `notebook_logs` in S3. |
| **Partial outbox + uninstall** | Pending uploads die with the app data. |
| **Missing S3 objects** | Pull refuses to advance; stuck until a device that still has the file re-pushes. |
| **Documents mirror auto-import after uninstall** | Written on pull; manual last resort only. |
| **Instant first paint after Play install** | Cold start / first sync delay (Render ~30–60s). |
| **Intentional Wipe / Delete / Lasso Cut** | By design, after confirm. |
| **Pre-v63 misfiles already synced** | Wrong-dest Moves from older tip may still sit in a parent notebook on device/cloud. |
| **SDK fence microsecond edge** | Theoretical pen-up at the exact instant a scroll starts; firmware may deliver or drop an in-progress stroke when raw drawing pauses. |

---

## Related product docs (agent store / design notes)

These live in the project agent store, not this GitHub tree: page-move safety (v46–v63), sync/restore SoT + Hand off (v64), Play cutover checklist, ink correctness (v13–v14). The claims above are what tip `main` code and tests enforce as of the versions cited.
