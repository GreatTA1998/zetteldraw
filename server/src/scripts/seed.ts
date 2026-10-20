/**
 * Loads the demo library (src/demo.ts): notebooks via normal push, boards via
 * SQL + storage.put (BOARD push is retired — Ready notebook_logs carry ink).
 * Rows that already exist are left alone, so re-running it never undoes a
 * rename or reorder.
 *
 *   docker compose run --rm sync node dist/scripts/seed.js
 */
import { existsSync } from "node:fs";
import { migrateAccounts } from "../accounts.js";
import { loadConfig } from "../config.js";
import { createPool, migrate } from "../db.js";
import { demoLibrary } from "../demo.js";
import { S3InkStorage } from "../storage.js";
import { push } from "../sync.js";

if (existsSync(".env")) {
  process.loadEnvFile(".env");
}

const config = loadConfig();
const pool = createPool(config.databaseUrl, config.databaseCaCert);
const schemaVersion = await migrate(pool, config.migrationsDir);
await migrateAccounts(pool, config.accountMigrationsDir);
const storage = S3InkStorage.fromConfig(config.s3);
await storage.ensureBucket();

const library = demoLibrary();
const ids = [...library.notebooks, ...library.boards].map((r) => r.id);
const existing = new Set(
  (
    await pool.query<{ id: string }>(
      "SELECT id FROM notebooks WHERE id = ANY($1::uuid[]) UNION SELECT id FROM boards WHERE id = ANY($1::uuid[])",
      [ids],
    )
  ).rows.map((r) => r.id),
);
const notebooks = library.notebooks.filter((n) => !existing.has(n.id));
const boards = library.boards.filter((b) => !existing.has(b.id));

await push(pool, storage, {
  schema_version: schemaVersion,
  device_id: "demo-seed",
  notebooks,
  boards: [],
  logs: [],
  links: [],
  blobs: {},
});

for (const b of boards) {
  if (b.ink_hash) {
    await storage.put(b.ink_hash, library.blobs.get(b.ink_hash)!);
  }
  await pool.query(
    `INSERT INTO boards (
       id, notebook_id, position, ink_hash, ink_bytes, thumb_hash,
       conflict_of, created_at, updated_at, deleted_at, rev
     ) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10, nextval('sync_rev'))`,
    [
      b.id,
      b.notebook_id,
      b.position,
      b.ink_hash,
      b.ink_bytes,
      b.thumb_hash ?? null,
      b.conflict_of,
      b.created_at,
      b.updated_at,
      b.deleted_at,
    ],
  );
}

await pool.end();
console.log(
  `seeded ${notebooks.length} notebooks and ${boards.length} pages ` +
    `(${existing.size} already present)`,
);
