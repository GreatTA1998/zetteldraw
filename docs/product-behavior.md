# Product behavior (canonical)

Short description of **what Zetteldraw does** and **how the Boox UI works**. Use this to stay aligned and to **prevent regressions**. Detail and proofs live elsewhere — do not invent features beyond this doc and the linked specs.

| Deeper topic | Spec |
| --- | --- |
| Irreversible bugs + enforced guarantees | [CORRECTNESS.md](./CORRECTNESS.md) |
| Live writer, Prepare transfer, Hand off | Prefer [CORRECTNESS.md](./CORRECTNESS.md) + README sync sections; store design notes if present |
| Ink / sync architecture (locked) | [ink-sot-rewrite.md](./ink-sot-rewrite.md), [server-slim-plan.md](./server-slim-plan.md) |

Tip APK: [https://zetteldraw.com/apk](https://zetteldraw.com/apk). Target device: **Boox Go 7 Color II**.

---

## What it is

Zetteldraw is a **handwriting-first notebook** for Boox. Live ink uses the Onyx `TouchHelper` raw/scribble path (not Android `View` drawing). Day-to-day writing is **local-first**: at launch every local notebook and page shows with its ink, and the pen works offline or online; sync runs in the background and never gates the screen.

A **notebook** is one continuous sheet of paper. A **page** is a slice of that sheet (cut, moved, or deleted). A **stroke** is points on the paper — it has an id and **no page**. Sync is one append-only ink log per notebook. **Only one live-writer Boox** may edit; others are read-only until Hand off. The web companion is **read-only** for ink and library edits (Hand off / lease ceremony only).

Seed top-level notebooks: comedy, journal, actions.life, miscellaneous. Scratchpad is the pinned endless blank sheet outside the tree.

---

## Main UI chrome

**Top bars.** Scratchpad (sheet icon) | top-level notebooks | **+**. Selecting a notebook with children opens another bar of **only those children**; deeper selection adds more bars. **Underline is leaf-only** (the open notebook, not ancestors). Long-press a tab: rename, **Place under…**, make top-level, or delete. Delete promotes children to the deleted notebook’s parent (or top-level) and sends that notebook’s own pages to Scratchpad.

**Tool row.** Undo · Redo | Pen · Eraser · Lasso. Active tool gets the same 2 dp underline. Pen is **black only** (fountain ~4.75 px). Eraser deletes whole strokes; Wipe clears a page (via **⋮**). One `ToolController.setTool` path keeps chrome, `PageInkView`, and the raw brush aligned. Root overlays must not cover the tool row.

**Drawing area.** Continuous vertical scroll of pages split by a dashed line. Finger scrolls; stylus draws. Every list ends with one trailing blank. First stroke on the blank stores it and grows a new blank. Page chrome: top-left **k/n**; bottom-left link refs then **Link · Reorder**; bottom-right **Move · ⋮**.

**Mode UIs.** Link and Reorder use a **bottom snackbar** with Confirm/Cancel and normal nav (bars + scroll). Do **not** fold temporary mode chrome into the top bars or rename the standing Link / Move / Reorder labels. Move and Place under use the **shared hierarchy notebook menu** (wide multi-column indented tree), not snackbar+nav.

**Blank pages.** Link / Reorder / Move visible but disabled (same alpha). On the trailing blank, **⋮** stays visible with that disabled look; taps no-op.

**Tap feedback.** Quiet ambient micro-sounds on UI `ACTION_DOWN` (cycle leaf → water → wood → fabric → pebble). Ink path stays silent.

---

## Notebook open and navigation

- **First open** of a collection → last page (Scratchpad: trailing blank; notebook: last inked, or first if empty).
- **Return** → restore prior scroll (session + SharedPreferences). Page **links** still jump to the linked page.
- Paint only when the ink-log sheet is **Ready**. Page counts and `k/n` come from the Ready paper projection (ink log), not stale Room board rows.
- Switching collections clears previous ink if the target sheet is not Ready yet (no parent ink under a child’s tab).

---

## Page Link, Reorder, Move, Place under

| Control | Behavior |
| --- | --- |
| **Link** | Snackbar; navigate to target; Confirm stores two page ids. Copy: *Linking A to B*. |
| **Reorder** | Snackbar; target = page under viewport midline; Confirm places source **before** target (cross-notebook allowed). Trailing blank → append. Dest = **open collection**. |
| **Move** | Hierarchy menu; pick notebook or **+ New notebook**; page appends as newest (place-before trailing blank; page id + move safety kept). |
| **Place under…** | Same hierarchy menu; pick parent (self/descendants omitted). Sets `parent_id` only — does not merge sheets. |
| **⋮** | Wipe / Delete page. |

Move / reorder resolve by **page id**; cross-notebook dest rewrite before source tear; Confirm disabled while lists load or on invalid source.

---

## Lasso / selection

1. Circle ink → majority-in-outline select (paper coordinates, including across the dashed mark).
2. Floating pill above the box: **Cut · Copy · Delete** (optional Paste if clipboard full).
3. Drag moves the group in the visible viewport. After drag commit, **selection stays active** (menu + handles) until tap-outside / Cut / Delete / tool change — Notes-like.
4. Tool **stays Lasso** after move/cancel (no auto-switch to Pen).
5. Cross-page transport: **Copy/Cut → normal scroll/nav → long-press Paste** at the hold point. No edge-scroll-during-drag; no top tap-anywhere paste bar.

---

## Sync model (locked)

**One live SoT Boox per Google account.** Cloud is **backup** while that device is leased. Day-to-day: live device **uploads**; others read-only until Hand off.

| Role | Ink |
| --- | --- |
| **Live writer** | **Push-only** — never adopt remote over local (except equal/idempotent). Never short-overwrite longer local disk. |
| **Non-writer / first restore / Hand off claim** | Catch-up pull may adopt longer remote. |

**Ceremony:** live Boox Settings → **Prepare transfer** (final sync + server ack; stays on device) → computer web Settings → **Hand off** → new device signs in and **claims**. Hand off stays **disabled** until Prepare transfer **or** higher-friction lost-device confirm, and backup is ready. Cancel Hand off restores the previous live writer. Never-synced device: pull before push; untouched seeds must not clobber cloud.

Web titles/thumbs are **not** a restore-safe proof. Multi-device concurrent edit of one notebook is **not** supported (no stroke CRDT).

---

## Web companion (read-only)

https://zetteldraw.com — notebook bars (nested children), dense thumbnail grid, full-page viewer with neighbor peeks and tappable page links. No drawing; no rename/move/reorder from the browser. Intentional web writes: Hand off / Cancel / lost-device only.

---

## Must not regress (tester checklist)

Use this as a smoke list against tip/Play builds. Prefer failing closed over shipping a “heal” that shortens or clobbers ink.

**Always write**

- [ ] Cold launch offline: local notebooks open with ink; pen works before any sync success.
- [ ] Sync never blanks or blocks the drawing surface to wait for network.

**Chrome / tools**

- [ ] Leaf-only underline on nested notebooks; child bar lists only children.
- [ ] Parent ↔ child switch does not leave the other sheet’s ink painted while not Ready.
- [ ] Pen / Eraser / Lasso underline matches what the stylus does (single tool path).
- [ ] After lasso drag, tool stays **Lasso**; selection remains until tap-outside.
- [ ] Link · Reorder stay bottom-left snackbar flows; Move · Place under stay hierarchy menu; labels unchanged.
- [ ] Blank pages: Link/Reorder/Move disabled (visible); trailing blank **⋮** disabled-look, not invisible.

**Navigation**

- [ ] First open → last page; return → prior scroll; links still jump.
- [ ] `k/n` / page list match Ready ink-log projection (not a short Room-only list like “23/23” on a long Scratchpad).

**Pages / hierarchy**

- [ ] Reorder Confirm places before midline page in the **open** collection; Move appends via menu without requiring a dest visit.
- [ ] Cross-notebook move keeps page id; no silent drop mid-tear.
- [ ] Place under sets parent only; children’s pages stay on their own sheets.
- [ ] Delete notebook: children promote; deleted notebook’s pages → Scratchpad.

**Sync / restore**

- [ ] Second Boox cannot push while another is live (`not_live_writer`).
- [ ] While live: remote must not replace longer/different local ink; shorter remote must not install.
- [ ] Hand off disabled until Prepare transfer (or lost-device) **and** backup ready.
- [ ] Prepare transfer does not open a browser; works after Google Sign-In on the live device.
- [ ] New empty device cannot clobber cloud with seed placeholders.

**Explicit non-goals**

- Multi-device co-edit of one notebook without Hand off.
- Web library editing or drawing.
- Pen color menu / multi-color live ink (removed; black only).
- Edge-scroll while dragging a lasso selection; top tap-anywhere paste bar.
- Folding Link/Reorder/Move mode chrome into the top notebook bars.
- Silent cloud “heal” that shortens or overwrites the live writer’s local ink.
