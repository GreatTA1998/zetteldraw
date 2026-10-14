# Server slim plan (single live writer)

Canonical copy: project store `docs/server-slim-plan.md`.

**Invariant:** one live writer. Sync = live upload + pull catch-up / Hand off — not CRDT.  
LWW/conflict shelf = exception (Hand off / lost device), not the main mental model.

See also [ink-sot-rewrite.md](./ink-sot-rewrite.md).
