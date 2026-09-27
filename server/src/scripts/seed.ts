/**
 * Loads the demo library (src/demo.ts) through the normal push path, then renders
 * its thumbnails. Rows that already exist are left alone, so re-running it never
 * undoes a rename or reorder.
 *
 *   docker compose run --rm sync node dist/scripts/seed.js
 */
import { existsSync } from "node:fs";
import { loadConfig } from "../config.js";
import { createPool, migrate } from "../db.js";
import { demoLibrary } from "../demo.js";
import { S3InkStorage } from "../storage.js";
import { push } from "../sync.js";
import { Thumbnailer } from "../thumbs.js";

if (existsSync(".env")) {
  process.loadEnvFile(".env");
}

const config = loadConfig();
const pool = createPool(config.databaseUrl, config.databaseCaCert);
const schemaVersion = await migrate(pool, config.migrationsDir);
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
const blobs: Record<string, string> = {};
for (const b of boards) {
  if (b.ink_hash) {
    blobs[b.ink_hash] = library.blobs.get(b.ink_hash)!.toString("base64");
  }
}

await push(pool, storage, { schema_version: schemaVersion, device_id: "demo-seed", notebooks, boards, blobs });

const thumbs = new Thumbnailer(storage, config.page);
let rendered = 0;
for (const hash of library.blobs.keys()) {
  if (await thumbs.ensureThumb(hash)) {
    rendered++;
  }
}
await pool.end();
console.log(
  `seeded ${notebooks.length} notebooks and ${boards.length} pages ` +
    `(${existing.size} already present); rendered ${rendered} thumbnails`,
);
