# Ink SoT rewrite

Tip **v85 / 3.64** shipped. Cont branch: `cursor/ink-sot-rewrite-cont-8d38`.

**Goal:** modular SoT paths you can explain locally. LOC = gauge, not success metric.  
Hard override: reliability / reasonability over vanity LOC.

Canonical plan: project store `docs/ink-sot-rewrite.md`.

## Sync invariant (Elton)

**No multi-device editing.** Only ever one SoT local device (the **live writer**).

| Role | Sync |
| --- | --- |
| Live Boox | Uploads — sole writer of cloud primary |
| Cloud | Backup / Hand-off seed |
| Other devices / web | Read-only until Hand off (or lost-device) + claim |
| Hand off / lost device | Exception ceremony — then the new device is the only writer |

Explain sync as: *live device uploads; others pull to catch up or stay read-only until Hand off.*

LWW / conflict shelves = **exception path** only — not the main mental model.  
Prefer deleting complexity that exists only for concurrent multi-writer editing.  
Length monotonicity (`InkLogApply` / server `resolveLog`) protects the live primary from short clobber (backup integrity, not peer merge).

Details: [server-slim-plan.md](./server-slim-plan.md).

## Modules

`InkLogApply` → `InkSheetStore` → `InkLogSync` / `SyncEngine` → `CollectionOpen` paint.

## DELETE toward single-writer

Among other dual-path deletes: multi-writer merge mental model in comments/tests; keep lease + Hand off; keep conflict shelf as exception only.
