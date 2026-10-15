# Codebase size — gauge, not goal

**Success:** modular, locally reason-able SoT paths.  
**LOC:** byproduct + gauge of refactor aggression. Report before/after when deleting dual paths; never optimize for the number alone.

Soft ambition via true simplification: Android main ~6k; server sync ~500–1k. See store `docs/codebase-size.md` and [ink-sot-rewrite.md](./ink-sot-rewrite.md).

## Android `app/src/main` (cloc Java code)

| Checkpoint | Code | Notes |
| --- | ---: | --- |
| v84 tip | 13 223 Java (13 511 w/ XML) | Baseline |
| v85 + extracts | 13 544 Java | Intentional rise |
| Delete dual wrappers (cont) | *(see commit)* | Call `InkSheetStore` / `InkLogSync` directly |
| **v87 sheet disk I/O → store** | `RoomBoardRepository` **2437** (−226); `InkSheetStore` **711** (+239) | Rewrite/flush/hold/reload/hash live in sheet module; Move orchestration stays in repo |
| **v89 meta pull LWW → MetaPullApply** | `RoomBoardRepository` **2376** (−75 vs v88); `MetaPullApply` **158** | Notebook/link/legacy-board pull LWW + conflict shelf; ink logs stay in `InkSheetStore` |

### Still fat (why)

| File | ~lines | Why |
| --- | ---: | --- |
| `CanvasActivity` | ~3550 | Move/Link/chrome + open |
| `RoomBoardRepository` | ~2376 | Move/tear + outbox + pull orchestration |
| `PageInkView` | ~2065 | Pen path — leave alone |
| `InkSheetStore` | ~711 | Open / apply / length / disk write |
| `MetaPullApply` | ~158 | Meta LWW exception shelf (beside `Lww`) |
