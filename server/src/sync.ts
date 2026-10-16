import { createHash, randomUUID } from "node:crypto";
import type pg from "pg";
import { isUnowned, ownedByUser, rowOwner, stampOwnership, type OwnerTable } from "./accounts.js";
import {
  resolveBoard,
  resolveLink,
  resolveLog,
  resolveNotebook,
  type BoardRow,
  type Decision,
  type LinkRow,
  type LogRow,
  type NotebookRow,
  type Status,
} from "./lww.js";
import type { InkStorage } from "./storage.js";
import { BadRequest, type PushBody } from "./validate.js";

/** Serializes pushes so revs commit in order and a pull never skips one. */
export const PUSH_LOCK = 42_4242;

/**
 * Blobs larger than this stay out of JSON (Base64). Clients upload/download them
 * via PUT/GET /sync/blob/:hash as raw octets. Keeps Boox heap under the ~256MB
 * growth limit during Prepare transfer / sync of large notebook logs.
 */
export const INLINE_BLOB_MAX_BYTES = 256 * 1024;

const BOARD_COLS = [
  "id",
  "notebook_id",
  "position",
  "ink_hash",
  "ink_bytes",
  "thumb_hash",
  "conflict_of",
  "created_at",
  "updated_at",
  "deleted_at",
] as const;
const NOTEBOOK_COLS = ["id", "title", "position", "parent_id", "created_at", "updated_at", "deleted_at"] as const;
const LOG_COLS = [
  "id",
  "notebook_id",
  "ink_hash",
  "ink_bytes",
  "slice_height",
  "conflict_of",
  "created_at",
  "updated_at",
  "deleted_at",
] as const;
const LINK_COLS = ["id", "source_id", "target_id", "created_at", "updated_at", "deleted_at"] as const;

export type ResultStatus = Status | "missing_blob" | "forbidden";

export interface PushResult {
  entity: "notebook" | "board" | "log" | "link";
  id: string;
  status: ResultStatus;
  rev: number;
}

export interface PullResponse {
  schema_version: number;
  cursor: number;
  has_more: boolean;
  notebooks: NotebookRow[];
  boards: BoardRow[];
  logs: LogRow[];
  links: LinkRow[];
  blobs: Record<string, string>;
}

/** Who is syncing: a Google user namespace, or the legacy unowned (device-token) bucket. */
export type SyncScope = { kind: "user"; userId: string } | { kind: "unowned" };

export function sha256Hex(bytes: Buffer): string {
  return createHash("sha256").update(bytes).digest("hex");
}

async function upsert(
  client: pg.PoolClient,
  table: "boards" | "notebooks" | "notebook_logs" | "page_links",
  cols: readonly string[],
  row: Record<string, unknown>,
): Promise<number> {
  const values = cols.map((c) => row[c]);
  const placeholders = cols.map((_, i) => `$${i + 1}`).join(", ");
  const updates = cols
    .filter((c) => c !== "id")
    .map((c) => `${c} = EXCLUDED.${c}`)
    .join(", ");
  const res = await client.query<{ rev: number }>(
    `INSERT INTO ${table} (${cols.join(", ")}, rev) VALUES (${placeholders}, nextval('sync_rev'))
     ON CONFLICT (id) DO UPDATE SET ${updates}, rev = EXCLUDED.rev
     RETURNING rev`,
    values,
  );
  return res.rows[0].rev;
}

async function restamp(
  client: pg.PoolClient,
  table: "boards" | "notebooks" | "notebook_logs" | "page_links",
  id: string,
): Promise<number> {
  const res = await client.query<{ rev: number }>(
    `UPDATE ${table} SET rev = nextval('sync_rev') WHERE id = $1 RETURNING rev`,
    [id],
  );
  return res.rows[0].rev;
}

async function applyDecision<T extends { id: string; rev: number }>(
  client: pg.PoolClient,
  table: "boards" | "notebooks" | "notebook_logs" | "page_links",
  cols: readonly string[],
  existing: T | null,
  decision: Decision<T>,
  scope: SyncScope,
): Promise<number> {
  let rev = existing?.rev ?? 0;
  if (decision.write) {
    rev = await upsert(client, table, cols, decision.write as Record<string, unknown>);
  } else if (decision.restamp && existing) {
    rev = await restamp(client, table, existing.id);
  }
  if (decision.copy && (table === "boards" || table === "notebook_logs")) {
    const copy = decision.copy as { id: string };
    const cols = table === "boards" ? BOARD_COLS : LOG_COLS;
    await upsert(client, table, cols, decision.copy as unknown as Record<string, unknown>);
    await maybeStamp(client, table, copy.id, scope);
  }
  return rev;
}

async function maybeStamp(
  client: pg.PoolClient,
  table: OwnerTable,
  rowId: string,
  scope: SyncScope,
): Promise<void> {
  if (scope.kind === "user") {
    await stampOwnership(client, table, rowId, scope.userId);
  }
}

async function assertWritable(
  client: pg.PoolClient,
  table: OwnerTable,
  rowId: string,
  scope: SyncScope,
): Promise<boolean> {
  const owner = await rowOwner(client, table, rowId);
  if (scope.kind === "user") {
    return owner === null || owner === scope.userId;
  }
  // Device-token / unowned scope: only touch rows that are still unowned.
  return owner === null;
}

/**
 * One push spine for notebooks / boards / logs / links: writable → lock row →
 * resolve → apply → stamp. Board/log pass {@code blobMissing} for ink hashes
 * not yet in storage or this push's blob map.
 */
async function pushEntityGroup<TExisting extends { id: string; rev: number }, TIncoming extends { id: string }>(
  client: pg.PoolClient,
  scope: SyncScope,
  results: PushResult[],
  entity: PushResult["entity"],
  table: OwnerTable,
  cols: readonly string[],
  rows: TIncoming[],
  resolve: (existing: TExisting | null, incoming: TIncoming) => Decision<TExisting>,
  blobMissing?: (row: TIncoming) => boolean,
): Promise<void> {
  for (const incoming of rows) {
    if (blobMissing?.(incoming)) {
      results.push({ entity, id: incoming.id, status: "missing_blob", rev: 0 });
      continue;
    }
    if (!(await assertWritable(client, table, incoming.id, scope))) {
      results.push({ entity, id: incoming.id, status: "forbidden", rev: 0 });
      continue;
    }
    const existing =
      (await client.query<TExisting>(`SELECT * FROM ${table} WHERE id = $1 FOR UPDATE`, [incoming.id]))
        .rows[0] ?? null;
    const decision = resolve(existing, incoming);
    const rev = await applyDecision(client, table, cols, existing, decision, scope);
    if (decision.write || !existing) {
      await maybeStamp(client, table, incoming.id, scope);
    }
    results.push({ entity, id: incoming.id, status: decision.status, rev });
  }
}

function visibilitySql(table: OwnerTable, alias: string, scope: SyncScope, userParam: number): string {
  if (scope.kind === "user") {
    return ownedByUser(table, alias, userParam);
  }
  return isUnowned(table, alias);
}

export async function push(
  pool: pg.Pool,
  storage: InkStorage,
  body: PushBody,
  scope: SyncScope = { kind: "unowned" },
): Promise<PushResult[]> {
  for (const [hash, b64] of Object.entries(body.blobs)) {
    const bytes = Buffer.from(b64, "base64");
    if (sha256Hex(bytes) !== hash) {
      throw new BadRequest(`blob ${hash} does not match its sha256`);
    }
    await storage.put(hash, bytes);
  }
  const missing = new Set<string>();
  for (const row of [...body.boards, ...body.logs]) {
    const hash = row.ink_hash;
    if (hash && row.deleted_at === null && !(hash in body.blobs) && !missing.has(hash)) {
      if (!(await storage.has(hash))) {
        missing.add(hash);
      }
    }
  }

  const results: PushResult[] = [];
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    await client.query("SELECT pg_advisory_xact_lock($1)", [PUSH_LOCK]);
    const inkBlobMissing = (row: { ink_hash: string | null; deleted_at: number | null }) =>
      !!(row.ink_hash && row.deleted_at === null && missing.has(row.ink_hash));
    await pushEntityGroup<NotebookRow, (typeof body.notebooks)[number]>(
      client, scope, results, "notebook", "notebooks", NOTEBOOK_COLS, body.notebooks,
      (existing, incoming) => resolveNotebook(existing, incoming));
    await pushEntityGroup<BoardRow, (typeof body.boards)[number]>(
      client, scope, results, "board", "boards", BOARD_COLS, body.boards,
      (existing, incoming) => resolveBoard(existing, incoming, randomUUID), inkBlobMissing);
    await pushEntityGroup<LogRow, (typeof body.logs)[number]>(
      client, scope, results, "log", "notebook_logs", LOG_COLS, body.logs,
      (existing, incoming) => resolveLog(existing, incoming, randomUUID), inkBlobMissing);
    await pushEntityGroup<LinkRow, (typeof body.links)[number]>(
      client, scope, results, "link", "page_links", LINK_COLS, body.links,
      (existing, incoming) => resolveLink(existing, incoming));
    await client.query("COMMIT");
  } catch (err) {
    await client.query("ROLLBACK").catch(() => {});
    throw err;
  } finally {
    client.release();
  }
  return results;
}

/** Live primary log metadata (no blobs) for length reconciliation after cursor catch-up. */
export interface LogHead {
  id: string;
  notebook_id: string | null;
  ink_hash: string;
  ink_bytes: number;
  slice_height: number;
  created_at: number;
  updated_at: number;
  rev: number;
}

/**
 * Heads of live notebook logs visible to {@code scope}. Used when a device's
 * global pull cursor has advanced past an older log rev (e.g. Scratchpad stuck
 * short while newer notebooks keep bumping the cursor) so the client can still
 * adopt a longer cloud primary without replaying the whole library.
 */
export async function logHeads(pool: pg.Pool, scope: SyncScope = { kind: "unowned" }): Promise<LogHead[]> {
  const params: unknown[] = [];
  let userParam = 0;
  if (scope.kind === "user") {
    params.push(scope.userId);
    userParam = params.length;
  }
  const lVis = visibilitySql("notebook_logs", "l", scope, userParam);
  const res = await pool.query<LogHead>(
    `SELECT l.id, l.notebook_id, l.ink_hash, l.ink_bytes, l.slice_height,
            l.created_at, l.updated_at, l.rev
     FROM notebook_logs l
     WHERE l.deleted_at IS NULL AND l.conflict_of IS NULL AND l.ink_hash IS NOT NULL AND ${lVis}
     ORDER BY l.id`,
    params,
  );
  return res.rows;
}

export async function pull(
  pool: pg.Pool,
  storage: InkStorage,
  schemaVersion: number,
  since: number,
  limit: number,
  scope: SyncScope = { kind: "unowned" },
): Promise<PullResponse> {
  const params: unknown[] = [since];
  let userParam = 0;
  if (scope.kind === "user") {
    params.push(scope.userId);
    userParam = params.length;
  }
  const nVis = visibilitySql("notebooks", "n", scope, userParam);
  const bVis = visibilitySql("boards", "b", scope, userParam);
  const lVis = visibilitySql("notebook_logs", "l", scope, userParam);
  const kVis = visibilitySql("page_links", "k", scope, userParam);
  params.push(limit + 1);
  const limitParam = params.length;

  const changes = await pool.query<{ kind: "notebook" | "board" | "log" | "link"; id: string; rev: number }>(
    `SELECT kind, id, rev FROM (
       SELECT 'notebook'::text AS kind, n.id, n.rev FROM notebooks n WHERE n.rev > $1 AND ${nVis}
       UNION ALL
       SELECT 'board', b.id, b.rev FROM boards b WHERE b.rev > $1 AND ${bVis}
       UNION ALL
       SELECT 'log', l.id, l.rev FROM notebook_logs l WHERE l.rev > $1 AND ${lVis}
       UNION ALL
       SELECT 'link', k.id, k.rev FROM page_links k WHERE k.rev > $1 AND ${kVis}
     ) changes
     ORDER BY rev
     LIMIT $${limitParam}`,
    params,
  );
  const hasMore = changes.rows.length > limit;
  const page = changes.rows.slice(0, limit);
  const cursor = page.length > 0 ? page[page.length - 1].rev : since;
  const maxRev = cursor;
  const notebookIds = page.filter((c) => c.kind === "notebook").map((c) => c.id);
  const boardIds = page.filter((c) => c.kind === "board").map((c) => c.id);
  const logIds = page.filter((c) => c.kind === "log").map((c) => c.id);
  const linkIds = page.filter((c) => c.kind === "link").map((c) => c.id);

  // Rows can be re-stamped by a concurrent push after the change scan; the rev bound keeps the page consistent.
  const notebooks = notebookIds.length
    ? (
        await pool.query<NotebookRow>(
          "SELECT * FROM notebooks WHERE id = ANY($1::uuid[]) AND rev <= $2 ORDER BY rev",
          [notebookIds, maxRev],
        )
      ).rows
    : [];
  const boards = boardIds.length
    ? (
        await pool.query<BoardRow>("SELECT * FROM boards WHERE id = ANY($1::uuid[]) AND rev <= $2 ORDER BY rev", [
          boardIds,
          maxRev,
        ])
      ).rows
    : [];

  const logs = logIds.length
    ? (
        await pool.query<LogRow>(
          "SELECT * FROM notebook_logs WHERE id = ANY($1::uuid[]) AND rev <= $2 ORDER BY rev",
          [logIds, maxRev],
        )
      ).rows
    : [];

  const links = linkIds.length
    ? (
        await pool.query<LinkRow>(
          "SELECT * FROM page_links WHERE id = ANY($1::uuid[]) AND rev <= $2 ORDER BY rev",
          [linkIds, maxRev],
        )
      ).rows
    : [];

  const blobs: Record<string, string> = {};
  const loadBlob = async (hash: string, kind: string, id: string) => {
    if (hash in blobs) {
      return;
    }
    if (!(await storage.has(hash))) {
      // Fail the whole pull page so the client does not advance its cursor past
      // unrehydratable rows. Prefer a clear 400 over a raw S3 NoSuchKey 500.
      throw new BadRequest(
        `missing ink blob ${hash} for ${kind} ${id}; re-sync from a device that still has the file`,
      );
    }
    const bytes = await storage.get(hash);
    // Large logs (Scratchpad can be 14MB+) must not Base64 into the pull JSON —
    // that alone OOMs a 256MB Boox heap. Client fetches them via GET /sync/blob/:hash.
    if (bytes.length > INLINE_BLOB_MAX_BYTES) {
      return;
    }
    blobs[hash] = bytes.toString("base64");
  };
  for (const row of [
    ...boards.map((b) => ({ hash: b.ink_hash, deleted: b.deleted_at, kind: "board", id: b.id })),
    ...logs.map((l) => ({ hash: l.ink_hash, deleted: l.deleted_at, kind: "notebook_log", id: l.id })),
  ]) {
    if (row.hash && row.deleted === null) {
      await loadBlob(row.hash, row.kind, row.id);
    }
  }
  return { schema_version: schemaVersion, cursor, has_more: hasMore, notebooks, boards, logs, links, blobs };
}
