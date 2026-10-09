import { createHash, randomUUID } from "node:crypto";
import type pg from "pg";
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

export type ResultStatus = Status | "missing_blob";

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
): Promise<number> {
  let rev = existing?.rev ?? 0;
  if (decision.write) {
    rev = await upsert(client, table, cols, decision.write as Record<string, unknown>);
  } else if (decision.restamp && existing) {
    rev = await restamp(client, table, existing.id);
  }
  if (decision.copy && table === "boards") {
    await upsert(client, "boards", BOARD_COLS, decision.copy as unknown as Record<string, unknown>);
  }
  if (decision.copy && table === "notebook_logs") {
    await upsert(client, "notebook_logs", LOG_COLS, decision.copy as unknown as Record<string, unknown>);
  }
  return rev;
}

export async function push(pool: pg.Pool, storage: InkStorage, body: PushBody): Promise<PushResult[]> {
  for (const [hash, b64] of Object.entries(body.blobs)) {
    const bytes = Buffer.from(b64, "base64");
    if (sha256Hex(bytes) !== hash) {
      throw new BadRequest(`blob ${hash} does not match its sha256`);
    }
    await storage.put(hash, bytes);
  }
  const missing = new Set<string>();
  for (const board of body.boards) {
    const hash = board.ink_hash;
    if (hash && board.deleted_at === null && !(hash in body.blobs) && !missing.has(hash)) {
      if (!(await storage.has(hash))) {
        missing.add(hash);
      }
    }
  }
  for (const log of body.logs) {
    const hash = log.ink_hash;
    if (hash && log.deleted_at === null && !(hash in body.blobs) && !missing.has(hash)) {
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
    for (const incoming of body.notebooks) {
      const existing =
        (await client.query<NotebookRow>("SELECT * FROM notebooks WHERE id = $1 FOR UPDATE", [incoming.id]))
          .rows[0] ?? null;
      const decision = resolveNotebook(existing, incoming);
      const rev = await applyDecision(client, "notebooks", NOTEBOOK_COLS, existing, decision);
      results.push({ entity: "notebook", id: incoming.id, status: decision.status, rev });
    }
    for (const incoming of body.boards) {
      if (incoming.ink_hash && incoming.deleted_at === null && missing.has(incoming.ink_hash)) {
        results.push({ entity: "board", id: incoming.id, status: "missing_blob", rev: 0 });
        continue;
      }
      const existing =
        (await client.query<BoardRow>("SELECT * FROM boards WHERE id = $1 FOR UPDATE", [incoming.id])).rows[0] ??
        null;
      const decision = resolveBoard(existing, incoming, randomUUID);
      const rev = await applyDecision(client, "boards", BOARD_COLS, existing, decision);
      results.push({ entity: "board", id: incoming.id, status: decision.status, rev });
    }
    for (const incoming of body.logs) {
      if (incoming.ink_hash && incoming.deleted_at === null && missing.has(incoming.ink_hash)) {
        results.push({ entity: "log", id: incoming.id, status: "missing_blob", rev: 0 });
        continue;
      }
      const existing =
        (await client.query<LogRow>("SELECT * FROM notebook_logs WHERE id = $1 FOR UPDATE", [incoming.id]))
          .rows[0] ?? null;
      const decision = resolveLog(existing, incoming, randomUUID);
      const rev = await applyDecision(client, "notebook_logs", LOG_COLS, existing, decision);
      results.push({ entity: "log", id: incoming.id, status: decision.status, rev });
    }
    for (const incoming of body.links) {
      const existing =
        (await client.query<LinkRow>("SELECT * FROM page_links WHERE id = $1 FOR UPDATE", [incoming.id])).rows[0] ??
        null;
      const decision = resolveLink(existing, incoming);
      const rev = await applyDecision(client, "page_links", LINK_COLS, existing, decision);
      results.push({ entity: "link", id: incoming.id, status: decision.status, rev });
    }
    await client.query("COMMIT");
  } catch (err) {
    await client.query("ROLLBACK").catch(() => {});
    throw err;
  } finally {
    client.release();
  }
  return results;
}

export async function pull(
  pool: pg.Pool,
  storage: InkStorage,
  schemaVersion: number,
  since: number,
  limit: number,
): Promise<PullResponse> {
  const changes = await pool.query<{ kind: "notebook" | "board" | "log" | "link"; id: string; rev: number }>(
    `SELECT 'notebook' AS kind, id, rev FROM notebooks WHERE rev > $1
     UNION ALL
     SELECT 'board' AS kind, id, rev FROM boards WHERE rev > $1
     UNION ALL
     SELECT 'log' AS kind, id, rev FROM notebook_logs WHERE rev > $1
     UNION ALL
     SELECT 'link' AS kind, id, rev FROM page_links WHERE rev > $1
     ORDER BY rev
     LIMIT $2`,
    [since, limit + 1],
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
  for (const board of boards) {
    if (board.ink_hash && board.deleted_at === null && !(board.ink_hash in blobs)) {
      blobs[board.ink_hash] = (await storage.get(board.ink_hash)).toString("base64");
    }
  }
  for (const log of logs) {
    if (log.ink_hash && log.deleted_at === null && !(log.ink_hash in blobs)) {
      blobs[log.ink_hash] = (await storage.get(log.ink_hash)).toString("base64");
    }
  }
  return { schema_version: schemaVersion, cursor, has_more: hasMore, notebooks, boards, logs, links, blobs };
}
