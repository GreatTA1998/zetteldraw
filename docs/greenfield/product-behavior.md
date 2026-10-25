# Product behavior (greenfield)

Condensed from the legacy [product-behavior.md](https://github.com/GreatTA1998/zetteldraw/blob/main/docs/product-behavior.md) and project preferences. Use this as the **acceptance list** for a rewrite. Tip [v86](https://github.com/GreatTA1998/zetteldraw/releases/tag/v86) is **reference behavior only** — do not port code blindly.

Target device: **Boox Go 7 Color II**. Tip APK for comparison: https://zetteldraw.com/apk.

---

## What it is

Zetteldraw is a **handwriting-first notebook** for Boox. Live ink uses the Onyx `TouchHelper` raw/scribble path (not Android `View` drawing). Day-to-day writing is **local-first**: at launch every local notebook and page shows with its ink; the pen works offline or online; sync never gates the drawing surface.

| Concept | Meaning |
| --- | --- |
| **Notebook** | One continuous sheet of paper |
| **Page** | A slice of that sheet (cut, moved, or deleted) |
| **Stroke** | Points on the paper — has an id and **no page** |
| **Ink SoT** | One append-only ink log per notebook (on disk), projected to pages when **Ready** |

Seed top-level notebooks: comedy, journal, actions.life, miscellaneous, **Trash**. **Scratchpad** is the pinned endless blank sheet outside the tree. Trash is a built-in recover bin (Move in/out), not a tombstone system.

The **web companion** (https://zetteldraw.com) is **read-only** for ink and library edits. Intentional web writes: Hand off / Cancel / lost-device lease ceremony only.

---

## UI chrome (summary)

**Top bars.** Scratchpad (sheet icon) | top-level notebooks | **+**. Selecting a notebook with children opens another bar of **only those children**; deeper selection adds more bars. **Underline is leaf-only** (the open notebook, not ancestors). Long-press a tab: rename, **Place under…**, make top-level, or delete. Delete promotes children to the deleted notebook’s parent (or top-level) and sends that notebook’s own pages to Scratchpad.

**Tool row.** Undo · Redo | Pen · Eraser · Lasso. Active tool gets a 2 dp underline. Pen is **black only** (fountain ~4.75 px). Eraser deletes whole strokes; Wipe clears a page (via **⋮**). One tool-set path keeps chrome, ink view, and the raw brush aligned. Root overlays must not cover the tool row.

**Drawing area.** Continuous vertical scroll of pages split by a dashed line. Finger scrolls; stylus draws. Every list ends with one trailing blank. First stroke on the blank stores it and grows a new blank. Page chrome: top-left **k/n**; bottom-left link refs then **Link · Reorder**; bottom-right **Move · ⋮**.

**Mode UIs.** Link and Reorder use a **bottom snackbar** with Confirm/Cancel and normal nav. Do **not** fold temporary mode chrome into the top bars. Move and Place under use the **shared hierarchy notebook menu** (wide multi-column packed tree).

**Pages.** Every Ready slice is real (blank or inked) with the same Link / Reorder / Move / ⋮ actions.

**Tap feedback.** Quiet ambient micro-sounds on UI `ACTION_DOWN` (cycle leaf → water → wood → fabric → pebble). Ink path stays silent.

Screenshots: [media/](./media/) — especially `boox-pen-surface-page-chrome.png`, `boox-notebook-tabs.png`, `boox-toolbar.png`.

---

## Navigation

- **First open** of a collection → last page (Scratchpad: trailing blank; notebook: last inked, or first if empty).
- **Return** → restore prior scroll (session + durable prefs). Page **links** still jump to the linked page.
- Paint only when the ink-log sheet is **Ready**. Page counts and `k/n` come from the Ready paper projection — never from a stale meta-only list.
- Switching collections clears previous ink if the target sheet is not Ready yet (no parent ink under a child’s tab).

---

## Page Link, Reorder, Move, Place under

| Control | Behavior |
| --- | --- |
| **Link** | Snackbar; navigate to target; Confirm stores two page ids. |
| **Reorder** | Snackbar; target = page under viewport midline; Confirm places source **before** target (cross-notebook allowed). Trailing blank → append. Dest = **open collection**. |
| **Move** | Hierarchy menu; pick notebook or **+ New notebook**; page appends as newest (page id + move safety kept). |
| **Place under…** | Same hierarchy menu; pick parent (self/descendants omitted). Sets parent only — does **not** merge sheets. |
| **⋮** | Wipe (confirm) / Delete = Move→Trash + Undo snackbar; recover via Move. |

Move / reorder resolve by **page id**; cross-notebook dest rewrite before source tear; Confirm disabled while lists load or on invalid source.

---

## Lasso / selection

1. Circle ink → majority-in-outline select (paper coordinates, including across the dashed mark).
2. Floating pill: **Cut · Copy · Delete** (optional Paste if clipboard full).
3. Drag moves the group. After drag commit, **selection stays active** until tap-outside / Cut / Delete / tool change.
4. Tool **stays Lasso** after move/cancel (no auto-switch to Pen).
5. Cross-page transport: **Copy/Cut → normal scroll/nav → long-press Paste**. No edge-scroll-during-drag; no top tap-anywhere paste bar.

---

## Sync model (locked)

**One live SoT Boox per Google account.** Cloud is **backup** while that device is leased. Day-to-day: live device **uploads**; others read-only until Hand off.

| Role | Ink |
| --- | --- |
| **Live writer** | **Push-only** — never adopt remote over local (except equal/idempotent). Never short-overwrite longer local disk. |
| **Non-writer / first restore / Hand off claim** | Catch-up pull may adopt longer remote. |

**Ceremony:** live Boox Settings → **Prepare transfer** (final sync + server ack; stays on device) → computer web Settings → **Hand off** → new device signs in and **claims**. Hand off stays **disabled** until Prepare transfer **or** higher-friction lost-device confirm, **and** backup is ready. Cancel Hand off restores the previous live writer. Never-synced device: pull before push; untouched seeds must not clobber cloud.

Web titles/thumbs are **not** a restore-safe proof. Multi-device concurrent edit of one notebook is **not** supported (no stroke CRDT).

Detail: [sync-model.md](./sync-model.md).

---

## Must not regress (tester checklist)

Prefer failing closed over shipping a “heal” that shortens or clobbers ink.

### Always write

- [ ] Cold launch offline: local notebooks open with ink; pen works before any sync success.
- [ ] Sync never blanks or blocks the drawing surface to wait for network.

### Chrome / tools

- [ ] Leaf-only underline on nested notebooks; child bar lists only children.
- [ ] Parent ↔ child switch does not leave the other sheet’s ink painted while not Ready.
- [ ] Pen / Eraser / Lasso underline matches what the stylus does (single tool path).
- [ ] After lasso drag, tool stays **Lasso**; selection remains until tap-outside.
- [ ] Link · Reorder stay bottom snackbar flows; Move · Place under stay hierarchy menu; labels unchanged.
- [ ] Blank Ready slices: Link / Reorder / Move / ⋮ enabled (same as inked).

### Navigation

- [ ] First open → last page; return → prior scroll; links still jump.
- [ ] `k/n` / page list match Ready ink-log projection (not a short meta-only list).

### Pages / hierarchy

- [ ] Reorder Confirm places before midline page in the **open** collection; Move appends via menu without requiring a dest visit.
- [ ] Cross-notebook move keeps page id; no silent drop mid-tear.
- [ ] Place under sets parent only; children’s pages stay on their own sheets.
- [ ] Delete notebook: children promote; deleted notebook’s pages → Scratchpad.

### Sync / restore

- [ ] Second Boox cannot push while another is live (`not_live_writer`).
- [ ] While live: remote must not replace longer/different local ink; shorter remote must not install.
- [ ] Hand off disabled until Prepare transfer (or lost-device) **and** backup ready.
- [ ] Prepare transfer does not open a browser; works after Google Sign-In on the live device.
- [ ] New empty device cannot clobber cloud with seed placeholders.

### Explicit product non-goals

- Multi-device co-edit of one notebook without Hand off.
- Web library editing or drawing.
- Pen color menu / multi-color live ink (black only).
- Edge-scroll while dragging a lasso selection; top tap-anywhere paste bar.
- Folding Link/Reorder/Move mode chrome into the top notebook bars.
- Silent cloud “heal” that shortens or overwrites the live writer’s local ink.

Code / architecture non-goals: [non-goals.md](./non-goals.md).
