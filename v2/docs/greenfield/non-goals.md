# Non-goals — what NOT to copy from the old codebase

The legacy Zetteldraw tree (tip through **v86**) is valuable as **behavior reference** and **failure archaeology**. It is **not** a scaffold for the greenfield app.

---

## Do not port these structures

| Anti-pattern | Why it hurt |
| --- | --- |
| **God files** (`CanvasActivity`, `RoomBoardRepository` open/sync/paint spine) | Too many questions in one type; tip fixes could not be locally reasoned |
| **Dual board + `.zdl` ink SoT** after Ready | Page counts / paint / length disagreed; short Room lists looked “authoritative” |
| **Silent pull-heal while live** (`ADOPT_LONGER` on every pull / log-heads for the live writer) | Raced the device that still had the real ink; “heal” became clobber |
| **Tip-band guards** (v78–v84 Ready/trust/cursor/log-heads bolt-ons) | Secondary heal paths that failed when the primary SoT was wrong; restamped revs / prefs length |
| **Prefs / “trusted length” as ink authority** | Normalized short disk as trusted; blocked real lengthen |
| **Global pull cursor as length authority** | Cursor past a notebook → heal never ran |
| **Meta heal that queues outbox** from incomplete projections | Pushed short/wrong ink as if local were SoT |
| **Dual selected-tool flags** / chrome vs stylus drift | Underline said Pen while stylus drew lasso |
| **Painting previous sheet while next not Ready** | Dating parent + Stacey child looked like one notebook |
| **Symmetric multi-writer LWW as the main sync story** | Product is single-writer; peer-merge complexity fought the lease model |
| **Web library edit / draw paths** | Companion is read-only except lease ceremony |
| **Pen color / Kaleido multi-color live ink** | Removed; black only |
| **Edge-scroll-during-lasso-drag + top tap-anywhere paste** | Replaced by Copy/Cut → long-press Paste |
| **Mode chrome folded into top notebook bars** | Link/Reorder stay bottom snackbars; Move/Place under stay hierarchy menu |
| **LOC golf / vanity deletes** that obscure Decision boundaries | Reliability > line count |
| **Continuing InkSheetStore carve inside the old tree** as the primary plan | Preference: greenfield from this pack |

---

## What *is* fair to reuse (conceptually)

Reuse **ideas and tests of irreversible invariants**, not file layout:

- Pure remote Decision: refuse shorter; keep live local; adopt longer only when allowed.
- Live-writer lease + Prepare / Hand off / claim + backup-status gates.
- Page-id move safety (dest before source tear).
- Ready-only paint; leaf-only underline; Place under = parent only.
- Onyx `TouchHelper` raw scribble as the pen path (API knowledge), reimplemented cleanly.
- Web read-only grid + nested bars + Hand off Settings UX (rebuild against screenshots).

Optional: keep tip v86 on a device for side-by-side UX comparison. **Do not** vendor `app/src/main` into the new repo.

---

## Stop list for agents

- Do **not** tip a new APK for greenfield docs work.
- Do **not** continue in-place `InkSheetStore` / god-file refactor as the mainline effort.
- Do **not** “just add one more heal” to the old sync spine.
- Do **start** from [product-behavior.md](./product-behavior.md) + [architecture.md](./architecture.md) + [media/](./media/).
