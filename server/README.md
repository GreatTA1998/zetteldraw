# zetteldraw sync server

A small service that the Boox app syncs with. Postgres stores notebook and board metadata. S3-compatible object storage stores the ink files (MinIO for local dev). Postgres is a backup and the future shared library; it is never on the pen-up write path.

Stack: TypeScript, Node 22, Fastify, `pg`, `@aws-sdk/client-s3`. I picked Node over Kotlin/Ktor because the service is mostly JSON and SQL, it starts in about a second in a small image, and the future web library will most likely be TypeScript too. The logic shared with the app (the LWW rule) is about twenty lines. Unit tests on both sides pin it down.

## Run locally

```bash
docker compose up -d --build --wait
curl localhost:8787/healthz          # {"ok":true,"schema_version":4}
```

| Service | Address | Credentials |
| --- | --- | --- |
| sync | `http://localhost:8787` | device token `dev-device-token` (set `DEVICE_TOKENS=a,b` to change) |
| Postgres | `localhost:55432` | `zetteldraw` / `zetteldraw` |
| MinIO console | `http://localhost:59001` | `zetteldraw` / `zetteldraw-secret` |

MinIO no longer publishes community images to Docker Hub, so compose uses `pgsty/minio`, a maintained drop-in build of the same server. Override it with `MINIO_IMAGE=...`.

To point the Boox at your Mac, build the app with `-Pzetteldraw.syncUrl=http://<mac-lan-ip>:8787 -Pzetteldraw.syncToken=dev-device-token`.

Without Docker: `npm ci && DATABASE_URL=... S3_ENDPOINT=... S3_BUCKET=... S3_ACCESS_KEY=... S3_SECRET_KEY=... DEVICE_TOKENS=... npm run dev`. Migrations run on startup, and the bucket is created if it is missing.

## Tests

```bash
npm test                                 # unit: LWW resolver, request validation, migration numbering
npm run test:integration                 # compose up → push/pull round trip → compose down
WITH_ANDROID=1 npm run test:integration  # also runs the Android client against it (two simulated devices)
KEEP=1 npm run test:integration          # leave the stack running afterwards
```

## Schema

`migrations/000N_*.sql` is the contract for both sides. `schema_version` is the highest migration number. The app's Room version must match it, and `SchemaContractTest` in the app checks that the columns match. To change the schema, add a new migration here and a matching Room migration, both with the same number.

## Protocol

Every `/sync/*` call sends `Authorization: Bearer <device token>` and `X-Zetteldraw-Schema: <n>`. A wrong token returns `401`. A schema mismatch returns `409 {"error":"schema_mismatch","server_schema":n}`.

`rev` is a server revision taken from one sequence shared by notebooks, boards, notebook logs, and page links. The client sends back the rev it last saw as `base_rev`; `0` means new. All times are epoch milliseconds.

`logs` is one append-only ink log per notebook. Two devices editing one notebook are editing that one object. Last-write-wins shelves the other log whole as a hidden conflict copy. A pull replaces the log. It does not merge strokes. Omit `logs` and it is treated as empty, so an older body still parses. The page blobs in `boards` stay; migration does not delete them.

`links` connects two pages by id and copies no ink. Last-write-wins, with no conflict copy. A delete of either page is a tombstone on the link. Omit `links` and it is treated as empty.

### `POST /sync/push`

```json
{
  "schema_version": 4,
  "device_id": "…",
  "notebooks": [{ "id", "title", "position", "parent_id", "created_at", "updated_at", "deleted_at", "base_rev" }],
  "boards":    [{ "id", "notebook_id", "position", "ink_hash", "ink_bytes", "thumb_hash", "conflict_of",
                  "created_at", "updated_at", "deleted_at", "base_rev" }],
  "logs":     [{ "id", "notebook_id", "ink_hash", "ink_bytes", "slice_height", "conflict_of",
                "created_at", "updated_at", "deleted_at", "base_rev" }],
  "links":    [{ "id", "source_id", "target_id", "created_at", "updated_at", "deleted_at", "base_rev" }],
  "blobs": { "<sha256 hex>": "<base64 ink file>" }
}
```

The response has one result per row: `{"entity":"board","id":"…","status":"…","rev":n}`.

| status | meaning |
| --- | --- |
| `applied` | `base_rev` matched, or the row was already identical (a retry). `rev` is the stored revision. |
| `conflict_won` | Someone else changed the row since `base_rev`, and this push has the newer `updated_at`. It is stored, and the old server version becomes a conflict copy if it had different ink. |
| `conflict_lost` | The server row is newer (the server wins ties). The server keeps it and gives it a fresh rev so it is pulled again. The pushed board is stored as a hidden conflict copy (`conflict_of = id`) if its ink differs. |
| `missing_blob` | `ink_hash` is not in `blobs` and not in storage. Resend with the blob. |

Blobs are checked against their sha256 (`400` if they don't match) and stored at `ink/<sha256>`. They are immutable and deduplicated. Pushes are serialized with an advisory lock, so revs commit in order and a pull never skips one.

Notebook create, rename and delete are ordinary notebook rows. A delete is a tombstone (`deleted_at`). The deleting device also pushes that notebook's pages, re-filed into the scratchpad (`notebook_id: null`). A device that pulls the tombstone moves any pages it still has in that notebook back to its scratchpad.

`parent_id` is null for a top-level notebook, or the id of its one parent. It is metadata: each notebook keeps its own ink log. A push that omits `parent_id` leaves the stored parent in place, so a client that lists notebooks flat does not clear the tree.

### `GET /sync/pull?since=<cursor>&limit=<n≤500>`

```json
{ "schema_version": 4, "cursor": 123, "has_more": false,
  "notebooks": [{ …row, "rev" }], "boards": [{ …row, "rev" }],
  "logs": [{ …row, "rev" }],
  "links": [{ …row, "rev" }],
  "blobs": { "<sha256>": "<base64>" } }
```

Returns rows with `rev > since` in rev order, with the ink for every live board on the page inlined. Store `cursor`, and repeat while `has_more` is true.
