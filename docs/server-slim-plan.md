# Server slim plan (single live writer)

Canonical detail: project store `docs/server-slim-plan.md`.

**Invariant:** no multi-device editing. One **live writer** Boox per Google account.  
Sync = **push from that device** + **pull catch-up** / **Hand off** — not CRDT or merge-of-equals.

Plain sentence: *live device uploads; others read-only until Hand off.*

LWW / conflict shelf = **exception** (Hand off race, lost-device, history bugs) — not the day-to-day model.

## Keep

- `sync.ts` push / pull / log-heads  
- Blob PUT/GET  
- `assertCanPush` + claimWriter lease  
- `resolveLog` byte-monotonic refuse shrink  
- Hand-off ceremony (`writer.ts`)

## Demote / refuse to grow

- Symmetric “either peer wins on `updated_at`” as the product story  
- Conflict shelf as primary UX  
- CRDT / stroke-merge / multi-writer CAS  

## Audit (`resolveLog`) — done

| Branch | Stance |
| --- | --- |
| Matching `base_rev` / insert | **Main** live upload — keep |
| `sameLog` retry | Keep |
| Refuse shorter | **Main** backup integrity — keep |
| Stale rev + clock LWW + whole-log shelf | **Exception** Hand-off safety — keep code; demote story |

Never remove: lease reject, log-heads, blob stream, refuse shorter.

Hard override: reliability > golf. See also [ink-sot-rewrite.md](./ink-sot-rewrite.md).
