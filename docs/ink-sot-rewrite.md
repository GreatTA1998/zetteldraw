# Ink SoT rewrite

Tip **v86 / 3.65** — live push-only ink.

**LOCKED:** project store `docs/system-architecture.md` (today slice).

## Sync

- Cloud = backup only while leased.
- **Live writer = push-only for ink** — `KEEP_LIVE_LOCAL` / no `ADOPT_LONGER` while live.
- Catch-up adopt only for non-writer / first restore.
- No short `.zdl` overwrite of longer local.

Follow-on: thin-shell gods, meta-only boards, non-writer UX — later slices.
