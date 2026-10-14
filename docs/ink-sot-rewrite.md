# Ink SoT rewrite

Branch: `cursor/ink-sot-rewrite-8d38`. Tip **v84** until a coherent safer slice ships.

**Goals:** Android main ~**6k**; server sync core ~**500–1k**. Make short-overwrite / cursor-skip / wrong-notebook-paint structurally impossible.

**Hard override:** never sacrifice reliability or ease of reasoning for vanity LOC. Drop lines only by truly simplifying while preserving specs; if a cut makes correctness harder to prove, keep the lines. Delete wrong abstractions / duplicate SoTs — do not golf.

## Slice A (landed on branch; tip still v84)

1. `InkLogApply` — exhaustive pure `Decision` (no shorter primary overwrite).
2. `RoomBoardRepository.applyRemoteLogLocked` — switch on `InkLogApply.decide` only.
3. `SyncEngine.syncInkLogLengths` — named length phase (was `reconcileLongerLogs`); still runs when pull fails.
4. Strip leftover “trusted page-count” wording; `invalidateOpenSheet` is reload helper, not length SoT.

**Hard override:** keep explicit decision types even if that adds a file — vanity LOC must not collapse SoTs.

## Module sketch

`InkLogApply` (pure) → `InkSheetStore` (extract later) → `InkLogSync` / `SyncEngine` length phase. `CollectionNav` = scroll only. Specs: store `docs/notebook-nav-correctness.md`, `docs/ink-sot-rewrite.md`.
