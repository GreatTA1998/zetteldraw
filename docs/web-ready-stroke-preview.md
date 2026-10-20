# Web Ready stroke preview

**Tip:** v168+ — companion page view replays Ready `.zdl` strokes; orphan ZDI thumb/render pipeline removed.

## Why

After Ready-only page lists, `/web/thumbs|renders` and `Thumbnailer` still decoded **legacy `.zdi`** page blobs into PNGs. Ready pages forced `ink_hash: null`, so the grid showed **blank rectangles**. That was a dual picture SoT and an orphan path.

## Architecture (chosen)

| Layer | Role |
| --- | --- |
| Server | Structure index (`readSliceIndex`) + `GET /web/notebooks/:id/ink` → raw Ready `.zdl` |
| Browser | `web/src/lib/zdl-paper.ts` full replay (APPEND/DELETE/TRANSLATE/TEAR/EXTEND) + canvas draw |
| Lists | Text-only page cells (no thumb images) |
| Viewer | Canvas stroke preview for the open page (and neighbor peeks) |

**Tradeoff vs prior ZDL→PNG measure (+532 server TEAR port):** TEAR geometry lives in the **client** (not counted in tip prod LOC `app/src/main` + `server/src`). Server deletes Thumbnailer / `@napi-rs/canvas` render / `/web/thumbs|renders`. One ink SoT: the Ready log. Optional baked PNG/WebP remains a secondary codec later if needed — do not resurrect ZDI thumbs.

## Smoke

1. Sync a notebook with ink to the server.
2. Open the companion → list shows page numbers (not blank image tiles).
3. Open a page → strokes match the Boox Ready page (not empty white).
4. `GET /web/thumbs/…` → 404.
