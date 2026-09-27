/**
 * Renders missing thumbnails for every live page with ink (pages synced before
 * thumbnails existed, or whose background render failed).
 *
 *   docker compose run --rm sync node dist/scripts/backfill-thumbs.js
 */
import { existsSync } from "node:fs";
import { loadConfig } from "../config.js";
import { createPool } from "../db.js";
import { S3InkStorage } from "../storage.js";
import { Thumbnailer } from "../thumbs.js";

if (existsSync(".env")) {
  process.loadEnvFile(".env");
}

const config = loadConfig();
const pool = createPool(config.databaseUrl, config.databaseCaCert);
const storage = S3InkStorage.fromConfig(config.s3);
const thumbs = new Thumbnailer(storage, config.page);

const hashes = (
  await pool.query<{ ink_hash: string }>(
    "SELECT DISTINCT ink_hash FROM boards WHERE ink_hash IS NOT NULL AND deleted_at IS NULL ORDER BY ink_hash",
  )
).rows.map((r) => r.ink_hash);

let rendered = 0;
let failed = 0;
for (const hash of hashes) {
  try {
    if (await thumbs.ensureThumb(hash)) {
      rendered++;
    }
  } catch (err) {
    failed++;
    console.error(`thumbnail for ${hash} failed:`, (err as Error).message);
  }
}
await pool.end();
console.log(`${hashes.length} ink files: rendered ${rendered}, already present ${hashes.length - rendered - failed}, failed ${failed}`);
process.exitCode = failed > 0 ? 1 : 0;
