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
