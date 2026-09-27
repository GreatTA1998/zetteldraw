import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import pg from "pg";

// bigint columns are epoch millis and revs; both fit in a JS number.
pg.types.setTypeParser(pg.types.builtins.INT8, (v) => Number(v));

export function createPool(databaseUrl: string): pg.Pool {
  return new pg.Pool({ connectionString: databaseUrl, max: 10 });
}

interface Migration {
  version: number;
  name: string;
  file: string;
}

export async function listMigrations(dir: string): Promise<Migration[]> {
  const files = (await readdir(dir)).filter((f) => /^\d{4}_.+\.sql$/.test(f)).sort();
  return files.map((file, i) => {
    const version = Number(file.slice(0, 4));
    if (version !== i + 1) {
      throw new Error(`migrations must be numbered 0001.. without gaps; found ${file}`);
    }
    return { version, name: file, file: path.join(dir, file) };
  });
}

/** Applies pending migrations and returns the schema version (highest migration). */
export async function migrate(pool: pg.Pool, dir: string): Promise<number> {
  const migrations = await listMigrations(dir);
  const client = await pool.connect();
  try {
    await client.query("SELECT pg_advisory_lock(7141)");
    await client.query(`CREATE TABLE IF NOT EXISTS schema_migrations (
      version    integer PRIMARY KEY,
      name       text NOT NULL,
      applied_at timestamptz NOT NULL DEFAULT now()
    )`);
    const applied = new Set(
      (await client.query<{ version: number }>("SELECT version FROM schema_migrations")).rows.map(
        (r) => r.version,
      ),
    );
    for (const m of migrations) {
      if (applied.has(m.version)) {
        continue;
      }
      const sql = await readFile(m.file, "utf8");
      await client.query("BEGIN");
      try {
        await client.query(sql);
        await client.query("INSERT INTO schema_migrations (version, name) VALUES ($1, $2)", [
          m.version,
          m.name,
        ]);
        await client.query("COMMIT");
      } catch (err) {
        await client.query("ROLLBACK");
        throw err;
      }
    }
    return migrations.length;
  } finally {
    await client.query("SELECT pg_advisory_unlock(7141)").catch(() => {});
    client.release();
  }
}
