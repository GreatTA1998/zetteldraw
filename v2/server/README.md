# Sync server (Hono stub)

Empty route stubs documenting push / pull / lease from [docs/greenfield/sync-model.md](../docs/greenfield/sync-model.md).

## Run

```bash
cd server
npm install
npm run dev
```

Default: [http://127.0.0.1:3847](http://127.0.0.1:3847) — `GET /health`, `POST /sync/push|pull`, lease + blob stubs return **501**.

## Routes (stubs)

| Method | Path | Role |
| --- | --- | --- |
| POST | `/sync/push` | Live-writer ink upload |
| POST | `/sync/pull` | Meta / catch-up |
| GET | `/sync/log-heads` | Per-notebook lengths |
| PUT/GET | `/sync/blob/:hash` | Raw ink blobs |
| POST | `/sync/lease/prepare` | Prepare transfer |
| POST | `/sync/lease/hand-off` | Hand off |
| POST | `/sync/lease/claim` | Claim live writer |
| POST | `/sync/lease/cancel` | Cancel Hand off |
| GET | `/sync/backup-status` | Hand off gate |

No Postgres / S3 wiring yet. Prefer Boox pen surface next; flesh these when InkLogSync needs a peer.
