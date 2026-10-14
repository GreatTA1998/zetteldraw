# Codebase size + LOC targets

Canonical copy for agents also lives in the project store. Tip baseline: **v84 / 3.63** (`e5831df`).

## Targets (Elton)

| Surface | Today | Target |
| --- | ---: | ---: |
| Android `app/src/main` | ~13.5k code | **~6k** |
| Server *actual sync* (pull/push/blob/LWW/writer-assert) | ~0.9–1.1k measured | **~1k** working; **500** not honest near-term |
| Web / auth / Hand-off ceremony | — | secondary (do not pad “sync”) |

**500 push-back:** `sync.ts`+`lww.ts`+`storage.ts` alone are **731** cloc; plus live-writer assert and payload checks ≈ **900–1100**. Getting to 500 means deleting real sync behavior or under-counting. Working target **~1k** measured sync core.

Full breakdown: keep this file updated when measuring; see store `docs/codebase-size.md` for tables. Architecture: [ink-sot-rewrite.md](./ink-sot-rewrite.md).
