# Ink SoT rewrite

Tip **v87 / 3.66** — sheet disk I/O lives in `InkSheetStore` (rewrite / flush / holdFlush / reload / hash). Behavior unchanged; Move orchestration still in `RoomBoardRepository`.

**v86 / 3.65** — live push-only ink (`KEEP_LIVE_LOCAL`).

**LOCKED:** project store `docs/system-architecture.md`. Greenfield preferred for further gods carve; tip only coherent safer modularity slices.

## Sync

- Cloud = backup only while leased.
- **Live writer = push-only for ink** — `KEEP_LIVE_LOCAL` / no `ADOPT_LONGER` while live.
- Catch-up adopt only for non-writer / first restore.
- No short `.zdl` overwrite of longer local.

## Sheet SoT (one breath)

open → Apply → **disk write** → length sync — all in `InkSheetStore`. Repo keeps projection + Move/tear orchestration.
