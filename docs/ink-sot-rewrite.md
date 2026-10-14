# Ink SoT rewrite

Tip **v85 / 3.64** shipped. Branch cont: `cursor/ink-sot-rewrite-cont-8d38`.

**Goal:** modular SoT paths you can explain locally. LOC = gauge, not success metric.

## Sync invariant (Elton)

**No multi-device editing.** One live-writer Boox.  
Explain sync as: *live device uploads; others read-only until Hand off.*  
LWW/conflict shelves = exception path only. Details: [server-slim-plan.md](./server-slim-plan.md).

## Modules

`InkLogApply` → `InkSheetStore` → `InkLogSync` / `SyncEngine` → `CollectionOpen` paint.

Hard override: reliability > golf.
