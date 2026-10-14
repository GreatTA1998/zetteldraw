# Ink SoT rewrite

Branch: `cursor/ink-sot-rewrite-8d38`. Tip **v84** until a coherent safer slice ships.

**Goal:** modular SoT paths you can explain locally. Soft LOC ambition (~6k / ~500–1k) is a **gauge**, not the success metric.

**Hard override:** reliability > golf. Delete dual paths when that clarifies SoT; report LOC deltas as signal only.

## Slice A (landed on branch; tip still v84)

1. `InkLogApply` — exhaustive pure `Decision` (no shorter primary overwrite).
2. `RoomBoardRepository.applyRemoteLogLocked` — switch on `InkLogApply.decide` only.
3. `SyncEngine.syncInkLogLengths` — named length phase (was `reconcileLongerLogs`); still runs when pull fails.
4. Strip leftover “trusted page-count” wording; `invalidateOpenSheet` is reload helper, not length SoT.

**Hard override:** keep explicit decision types even if that adds a file — vanity LOC must not collapse SoTs.

## Module sketch

`InkLogApply` (pure) → **`InkSheetStore`** (Ready + ensure + remote apply) → `SyncEngine.syncInkLogLengths`. `RoomBoardRepository` = projection / Move / meta. `CollectionNav` = scroll only.

## Shipped tip v85 / 3.64

- `InkSheetStore` + `InkLogApply` + `CollectionOpen` + trust purge
- Post-tip: `InkLogSync` extracted from `SyncEngine` (length phase module)

## Module sketch

`InkLogApply` → `InkSheetStore` → `InkLogSync` / `SyncEngine` → `CollectionOpen` paint.
