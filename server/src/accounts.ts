import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import type pg from "pg";

export const OWNER_TABLES = ["notebooks", "boards", "notebook_logs", "page_links"] as const;
export type OwnerTable = (typeof OWNER_TABLES)[number];

export interface GoogleUser {
  id: string;
  email: string | null;
}

export type ClaimStatus = "claimed" | "already_yours" | "already_taken" | "nothing_to_claim";

export interface ClaimResult {
  status: ClaimStatus;
  claimed_by: string | null;
  rows_claimed: number;
}

/** Applies account-migrations/*.sql without touching sync schema_version. */
export async function migrateAccounts(pool: pg.Pool, dir: string): Promise<void> {
  const files = (await readdir(dir))
    .filter((f) => /^\d{4}_.+\.sql$/.test(f))
    .sort();
  const client = await pool.connect();
  try {
    await client.query("SELECT pg_advisory_lock(7142)");
    await client.query(`CREATE TABLE IF NOT EXISTS account_migrations (
      version    integer PRIMARY KEY,
      name       text NOT NULL,
      applied_at timestamptz NOT NULL DEFAULT now()
    )`);
    const applied = new Set(
      (await client.query<{ version: number }>("SELECT version FROM account_migrations")).rows.map(
        (r) => r.version,
      ),
    );
    for (const file of files) {
      const version = Number(file.slice(0, 4));
      if (applied.has(version)) {
        continue;
      }
      const sql = await readFile(path.join(dir, file), "utf8");
      await client.query("BEGIN");
      try {
        await client.query(sql);
        await client.query("INSERT INTO account_migrations (version, name) VALUES ($1, $2)", [
          version,
          file,
        ]);
        await client.query("COMMIT");
      } catch (err) {
        await client.query("ROLLBACK");
        throw err;
      }
    }
  } finally {
    await client.query("SELECT pg_advisory_unlock(7142)").catch(() => {});
    client.release();
  }
}

export async function upsertUser(pool: pg.Pool | pg.PoolClient, user: GoogleUser, now = Date.now()) {
  await pool.query(
    `INSERT INTO users (id, email, created_at) VALUES ($1, $2, $3)
     ON CONFLICT (id) DO UPDATE SET email = COALESCE(EXCLUDED.email, users.email)`,
    [user.id, user.email, now],
  );
}

export async function getLibraryClaimer(pool: pg.Pool | pg.PoolClient): Promise<string | null> {
  const res = await pool.query<{ user_id: string }>("SELECT user_id FROM library_claim WHERE id = 1");
  return res.rows[0]?.user_id ?? null;
}

/**
 * Idempotent one-shot: first Google user to claim owns every row that has no
 * row_owners entry. Later callers with the same user get already_yours; a
 * different user gets already_taken (their namespace stays empty of legacy data).
 */
export async function claimUnownedLibrary(
  pool: pg.Pool,
  user: GoogleUser,
  now = Date.now(),
): Promise<ClaimResult> {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    await client.query("SELECT pg_advisory_xact_lock(7143)");
    await upsertUser(client, user, now);

    const existing = await getLibraryClaimer(client);
    if (existing === user.id) {
      await client.query("COMMIT");
      return { status: "already_yours", claimed_by: existing, rows_claimed: 0 };
    }
    if (existing !== null) {
      await client.query("COMMIT");
      return { status: "already_taken", claimed_by: existing, rows_claimed: 0 };
    }

    let rowsClaimed = 0;
    for (const table of OWNER_TABLES) {
      const res = await client.query(
        `INSERT INTO row_owners (table_name, row_id, user_id)
         SELECT $1, t.id, $2 FROM ${table} t
         WHERE NOT EXISTS (
           SELECT 1 FROM row_owners o WHERE o.table_name = $1 AND o.row_id = t.id
         )
         ON CONFLICT DO NOTHING`,
        [table, user.id],
      );
      rowsClaimed += res.rowCount ?? 0;
    }

    await client.query(
      `INSERT INTO library_claim (id, user_id, claimed_at) VALUES (1, $1, $2)`,
      [user.id, now],
    );
    await client.query("COMMIT");
    return {
      status: rowsClaimed === 0 ? "nothing_to_claim" : "claimed",
      claimed_by: user.id,
      rows_claimed: rowsClaimed,
    };
  } catch (err) {
    await client.query("ROLLBACK").catch(() => {});
    throw err;
  } finally {
    client.release();
  }
}

export async function stampOwnership(
  client: pg.PoolClient,
  table: OwnerTable,
  rowId: string,
  userId: string,
): Promise<void> {
  await client.query(
    `INSERT INTO row_owners (table_name, row_id, user_id) VALUES ($1, $2, $3)
     ON CONFLICT (table_name, row_id) DO NOTHING`,
    [table, rowId, userId],
  );
}

/** Owner of a row, or null if still unowned (legacy shared). */
export async function rowOwner(
  client: pg.Pool | pg.PoolClient,
  table: OwnerTable,
  rowId: string,
): Promise<string | null> {
  const res = await client.query<{ user_id: string }>(
    `SELECT user_id FROM row_owners WHERE table_name = $1 AND row_id = $2`,
    [table, rowId],
  );
  return res.rows[0]?.user_id ?? null;
}

/** SQL fragment: row is owned by $userIdParam (table_name is a literal). */
export function ownedByUser(tableName: OwnerTable, alias: string, userIdParam: number): string {
  return `EXISTS (
    SELECT 1 FROM row_owners o
    WHERE o.table_name = '${tableName}' AND o.row_id = ${alias}.id AND o.user_id = $${userIdParam}
  )`;
}

/** SQL fragment: row has no owner (legacy shared). */
export function isUnowned(tableName: OwnerTable, alias: string): string {
  return `NOT EXISTS (
    SELECT 1 FROM row_owners o WHERE o.table_name = '${tableName}' AND o.row_id = ${alias}.id
  )`;
}
