# Ink SoT rewrite

Tip **v89 / 3.68** — meta pull LWW (notebook / link / legacy board + conflict shelf) lives in `MetaPullApply`. Ink-log open / apply / length / disk write stay in `InkSheetStore`.

**v88 / 3.67** — Move ensureSheet for never-opened dest notebooks.

**v87 / 3.66** — sheet disk I/O in `InkSheetStore` (rewrite / flush / holdFlush / reload / hash).

**v86 / 3.65** — live push-only ink (`KEEP_LIVE_LOCAL`).

**LOCKED:** project store `docs/system-architecture.md`. Greenfield preferred for further gods carve; tip only coherent safer modularity slices. Do not densify server `lww.ts` shelf without Hand-off race tests.

## Sync

- Cloud = backup only while leased.
- **Live writer = push-only for ink** — `KEEP_LIVE_LOCAL` / no `ADOPT_LONGER` while live.
- Catch-up adopt only for non-writer / first restore.
- No short `.zdl` overwrite of longer local.
- Meta LWW / conflict shelves = **exception path** (Hand off / history), not day-to-day peer merge — see `MetaPullApply` + `Lww`.

## Sheet SoT (one breath)

open → Apply → disk write → length sync — all in `InkSheetStore`.  
Meta pull (notebook / link / legacy `.zdi` board) → `MetaPullApply`.  
Repo keeps projection + Move/tear + pull orchestration.
