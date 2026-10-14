# Sync model (locked)

**One live Boox writes. Cloud backs up. Prepare transfer → Hand off → claim — like an iPhone upgrade.**

Full ceremony history (legacy): [sync-restore-sot-design](https://github.com/GreatTA1998/zetteldraw/blob/main/docs/) notes / CORRECTNESS. This page is the short greenfield contract.

---

## Roles

| Role | Ink |
| --- | --- |
| **Live writer** | Sole device allowed to push ink. Day-to-day: **push-only**. Never adopt remote over local (except equal/idempotent). Never install shorter remote over longer local disk. |
| **Cloud** | Backup + web library + Hand off seed. Not a second writer. |
| **Non-writer / new device** | Read-only until Hand off + claim. Catch-up pull may **adopt longer** remote. |
| **Web companion** | Read-only ink/library. Writes only lease ceremony (Hand off / Cancel / lost-device). |

No multi-device co-edit. No stroke CRDT. LWW / conflict shelf = **exception** path only (ceremony races, corrupted history) — not the day-to-day model.

---

## Day-to-day

```
Live Boox  --push-->  Cloud (Postgres meta + S3 ink)
           <-pull--   (meta / thumbs / optional catch-up for non-writer;
                       live writer does NOT replace local ink bodies)
```

- Offline pen always works on local Ready ink.
- Sync never blanks the drawing surface waiting for network.
- Never-synced device: **pull before push**; untouched seed placeholders (`updated_at === 0`) must not clobber cloud.

---

## Ceremony: Prepare → Hand off → claim

```
Device 1 (live)                 Cloud                    Web / Device 2
───────────────                 ─────                    ──────────────
push until caught up  ------->  backup ready
Settings → Prepare transfer --> ack (stays on Boox)
                                unlock Hand off  <------  Settings → Hand off
push BLOCKED on Device 1
                                                     Device 2: sign in → pull → claim → live
```

| Step | Where | Rule |
| --- | --- | --- |
| **Prepare transfer** | Live Boox Settings | Final sync (outbox empty) + server ack. Does **not** open a browser. Requires Google Sign-In + sync on + live writer. |
| **Hand off** | Web Settings → Move to a new device | Disabled until Prepare **or** lost-device confirm, **and** backup-status ready. |
| **Lost device** | Web (secondary link + confirm) | Skips Prepare when old Boox is gone; still needs backup ready. |
| **Claim** | New Boox | First successful claim becomes live writer; Hand off window closes. |
| **Cancel Hand off** | Web | Restores previous live writer; clears prepare / lost-device flags. |

Screenshot: [media/web-hand-off.png](./media/web-hand-off.png).

---

## What must be impossible

| Unsafe path | Enforcement |
| --- | --- |
| Second Boox pushes while another is live | `403 not_live_writer` |
| Hand off without Prepare (and without lost-device) | UI disabled + server reject |
| Hand off while backup incomplete | UI disabled + server reject |
| Old device keeps writing after Hand off | Push/claim blocked for old device id |
| Empty seeds clobber cloud | Pull-before-push + seed reject |
| Silent cloud heal shortens live local ink | Live = push-only; refuse shorter; keep live local |

Web titles/thumbs ≠ restore-safe proof. Gate on real backup-status (logs + boards in object storage).
