# Architecture (greenfield target)

First-principles design for a **new** app. This is **not** a plan to carve `RoomBoardRepository` or thin-shell the existing `CanvasActivity`. Tip [v86](https://github.com/GreatTA1998/zetteldraw/releases/tag/v86) proved the **behavior spine** (push-only while live, refuse shorter). The legacy tree still failed reliability under tip-band complexity — rewrite so the bad states are **structurally impossible**.

---

## One breath

Local ink log is SoT for paper once Ready. Live Boox uploads; cloud is backup. UI paints Ready only. Sync cannot shorten or replace live local ink. Wrong notebook never paints another sheet’s strokes.

---

## Modules (target)

One question → one owner. No second SoT for the same question.

```
UI shell (activity / compose / views)
   ├── CollectionNav     scroll Y + first-open / return memory
   ├── ToolController    single selected tool → chrome + stylus
   └── InkSurface        Onyx TouchHelper; paints Ready paper only
            │
InkSheetStore            owns .zdl (or successor), Ready, pages(id)
            │
InkLogApply (pure)       remote vs local Decision — no I/O
            │
InkLogSync               push outbox; per-notebook length/catch-up pull
            │
NotebookMeta             hierarchy, titles, links, lease flags — never ink bodies
            │
Sync / Account           Google auth, live-writer lease, Prepare / claim
```

| Module | Owns | Must not |
| --- | --- | --- |
| **InkSheetStore** | On-disk ink log, Ready gate, `pages(notebookId)`, local tear/flush | HTTP, global sync cursors, prefs-as-length |
| **InkLogApply** | Exhaustive pure Decision: keep local / adopt longer / refuse shorter / equal | Touch disk, Room, network |
| **InkLogSync** | Outbox push; **per-id** length / catch-up | Paint UI; invent page lists from meta alone |
| **CollectionNav** | Scroll Y, first-open last page, return restore | Gate page count or ink length |
| **NotebookMeta** | Tree, titles, page links, board rows as **meta** | Replace or invent ink bodies |
| **UI shell** | Bind Ready + scroll; leaf underline; tools | Paint when not Ready; keep prior notebook ink after switch |
| **Lease / ceremony** | Live writer, Prepare transfer, Hand off, claim | Silent pull-heal while live |

Server stays: metadata in Postgres, ink blobs in S3, live-writer lease, web read-only library + Hand off UI. Prefer slim single-writer semantics; LWW / conflict shelf = **exception** (ceremony races / history bugs), not day-to-day co-edit.

---

## Invariants that make data loss structurally impossible

These are **design laws**, not tip footnotes.

| # | Law | Structural consequence |
| --- | --- | --- |
| **L1** | One live SoT device per Google account | Second writer cannot push (`not_live_writer`) |
| **L2** | Day-to-day sync = live uploads; others read-only until Hand off | No peer merge / stroke CRDT |
| **L3** | Cloud = backup while leased | Pull is catch-up / restore seed — not a second writer |
| **L4** | Ink log on disk is SoT for pages once **Ready** | Page lists / `k/n` never come from meta-only Room when a log exists |
| **L5** | Never short-overwrite longer local disk | Install path has **no** `OVERWRITE_WITH_SHORTER` |
| **L6** | Live writer: **push-only** for ink | While live, longer/different remote does **not** replace local (`KEEP_LIVE_LOCAL`); `ADOPT_LONGER` only for non-writer / first claim / restore |
| **L7** | Paint Ready only; clear on not-Ready switch | Wrong-notebook paint impossible |
| **L8** | Move / tear by page id; dest durable before source tear | No silent mid-tear drop |
| **L9** | Never-synced: pull before push; `updated_at === 0` seeds never clobber cloud | Empty device cannot wipe backup |
| **L10** | Hand off gated on Prepare (or lost-device) **and** backup ready | Foolproof cutover ceremony |

**Three irreversible bugs** (product): corrupted data, lost page data, lost data from bad sync/restore. UI thrash is secondary. Scale/O(n) is a first-class concern — but a scale “fix” must not regress reliability (prefer revert).

---

## Structural impossibility checklist (rewrite acceptance)

A greenfield design is not done until all of these are true **by construction** (tests + types / module boundaries), not by a late tip guard:

1. **Short remote cannot overwrite long local** — Decision enum has no shorter-overwrite; single install switch; disk write refuses shorter bytes.
2. **Live local cannot be replaced by remote body** while `allowAdoptRemote=false` (live lease).
3. **Global pull cursor is not length authority** — length/catch-up is per notebook id; cursor cannot “skip past” a heal forever.
4. **`pages(id)` empty unless Ready for that id** — UI clears previous sheet on not-Ready open.
5. **Leaf underline only** — parent+child never look like one sheet.
6. **Place under does not merge sheets** — `parent_id` only.
7. **One tool path** — chrome underline == stylus mode.
8. **Sync never blocks cold open / pen** — local Ready ink paints offline.

---

## Anti-patterns learned from tip bandages (v78–v86)

Do **not** rebuild these. They were emergency patches on a dual-SoT spine; several made loss *worse* or only papered over it.

| Tip band | What it tried | Lesson for greenfield |
| --- | --- | --- |
| **v78** | Ready-gate on nav | Necessary idea (paint Ready only) — but insufficient if pull can still shorten Ready |
| **v79–v80** | Refuse shorter + “trust” prefs | Prefs/trust-as-length **normalized short disk** as trusted; meta heal queued short push |
| **v81** | Global `LogLength` monotonic | Global **pull cursor** had already passed Scratchpad → lengthen never ran |
| **v82–v83** | `/sync/log-heads` reconcile bolt-on | Heal stayed a **secondary** path, not the open/sync SoT; blob fail aborted heal |
| **v84** | Dating/Stacey leaf underline + clear paint | Correct UX invariant — still unrelated to Scratchpad length loss |
| **v85–v86** | `InkLogApply`, push-only while live, disk guard | Right **laws** — still bolted onto a 13k LOC god-file tree |

### Explicit anti-patterns (ban in greenfield)

1. **God files** — open + sync + paint + chrome in one type (`CanvasActivity` / `RoomBoardRepository` scale).
2. **Dual ink SoT** — Room board rows **and** `.zdl` both claiming page lists / length after Ready.
3. **Silent pull-heal while live** — `ADOPT_LONGER` on every pull / log-heads for the live writer.
4. **Tip-band guards** that restamp revs, prefs “trusted length”, or heal-only footnotes without owning open/apply/sync.
5. **Meta heal that queues outbox** from incomplete Room projections.
6. **Multi-writer merge mental model** as the main sync story (symmetric LWW / CRDT-ish peer edit).
7. **Painting previous notebook ink** while the next sheet is not Ready.
8. **Porting legacy wrappers** “for compatibility” that keep two write paths alive.

See [non-goals.md](./non-goals.md) for the full “do not copy” list.

---

## Suggested build order (new repo)

1. **InkSheetStore + Ready + offline pen** — cold open with ink; no network required.
2. **InkLogApply pure core + tests** — refuse shorter; keep live local; adopt longer only when allowed.
3. **InkLogSync push + lease** — live uploads; non-writer cannot push.
4. **UI chrome + CollectionNav** — leaf underline, first-open / return, page chrome.
5. **Move / Link / Lasso** — page-id safety; lasso keep-selection.
6. **Web read-only + Hand off ceremony** — Prepare / Hand off / claim / backup-status gates.
7. **Scale** — streaming open/pull for large logs; do not regress reliability for LOC golf.

Reliability and ease of reasoning beat line-count targets. Prefer deleting wrong abstractions over documenting around them.
