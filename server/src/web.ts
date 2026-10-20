import type { FastifyInstance } from "fastify";
import type pg from "pg";
import { isUnowned, ownedByUser, type OwnerTable } from "./accounts.js";
import type { BoardRow, LogRow, NotebookRow } from "./lww.js";
import type { SyncScope } from "./sync.js";
import type { InkStorage } from "./storage.js";
import { BadRequest } from "./validate.js";
import { ackLostDevice, cancelHandOff, getWriterStatus, handOff } from "./writer.js";
import { readSliceIndex, ZdlFormatError } from "./zdl.js";
/**
 * Read endpoints for the web overview. Notebook/page mutations are refused —
 * the companion is strictly read-only for ink. The only write it offers is
 * Hand off / lost-device ack (live-writer lease), which does not touch notebook data.
 *
 * Page identity/order is Ready-only: {@code notebook_logs} + ZDL structure index.
 * Ink preview is the Ready {@code .zdl} bytes ({@code GET .../ink}); the browser
 * replays strokes. No separate thumbnail / ZDI picture pipeline.
 * Live {@code boards} rows are not a companion list SoT (Hand-off still scans
 * uncovered BOARD ink via {@link backupStatus}).
 */

export const SCRATCHPAD = "scratchpad";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export class NotFound extends Error {}
export class Forbidden extends Error {}

export interface WebNotebook {
  id: string;
  kind: "scratchpad" | "notebook";
  title: string;
  position: string | null;
  /** Null for Scratchpad and top-level notebooks. */
  parent_id: string | null;
  page_count: number;
  last_edited_at: number | null;
  updated_at: number | null;
  rev: number | null;
}

/** One side of a page link, resolved like Android's → / ← chrome. */
export interface WebPageLink {
  id: string;
  direction: "out" | "in";
  other_id: string;
  /** Scratchpad is `"scratchpad"`; otherwise a notebook UUID. */
  other_notebook_id: string;
  other_notebook_title: string;
  /** 1-based index among live pages in that notebook. */
  other_index: number;
  other_count: number;
}

export interface WebPage {
  id: string;
  notebook_id: string | null;
  position: string;
  ink_hash: string | null;
  ink_bytes: number;
  created_at: number;
  updated_at: number;
  rev: number;
  links: WebPageLink[];
}

export interface PagePlace {
  page_id: string;
  notebook_id: string;
  notebook_title: string;
  index: number;
  count: number;
}

const LIVE = "b.deleted_at IS NULL AND b.conflict_of IS NULL";

/** Ready-synthesized page list row (structure only; ink is the notebook log). */
type PageListRow = Pick<
  BoardRow,
  "id" | "notebook_id" | "position" | "ink_hash" | "ink_bytes" | "created_at" | "updated_at" | "rev"
>;

function scopeParams(scope: SyncScope): { sql: (table: "notebooks" | "boards", alias: string) => string; params: unknown[] } {
  if (scope.kind === "user") {
    return {
      sql: (table, alias) => ownedByUser(table, alias, 1),
      params: [scope.userId],
    };
  }
  return {
    sql: (table, alias) => isUnowned(table, alias),
    params: [],
  };
}

function logOwnSql(scope: SyncScope): { sql: string; params: unknown[] } {
  if (scope.kind === "user") {
    return { sql: ownedByUser("notebook_logs" as OwnerTable, "l", 1), params: [scope.userId] };
  }
  return {
    sql: `NOT EXISTS (SELECT 1 FROM row_owners o WHERE o.table_name = 'notebook_logs' AND o.row_id = l.id)`,
    params: [],
  };
}

async function readyLogForNotebook(
  db: pg.Pool | pg.PoolClient,
  notebookId: string | null,
  scope: SyncScope,
): Promise<LogRow | null> {
  const { sql, params } = logOwnSql(scope);
  const nb =
    notebookId === null
      ? "l.notebook_id IS NULL"
      : `l.notebook_id = $${params.length + 1}`;
  const res = await db.query<LogRow>(
    `SELECT l.* FROM notebook_logs l
     WHERE l.deleted_at IS NULL AND l.conflict_of IS NULL AND ${sql} AND ${nb}
     ORDER BY l.updated_at DESC, l.rev DESC
     LIMIT 1`,
    notebookId === null ? params : [...params, notebookId],
  );
  return res.rows[0] ?? null;
}

async function pagesFromReadyLog(
  storage: InkStorage,
  log: LogRow,
  notebookId: string | null,
): Promise<PageListRow[] | null> {
  if (!log.ink_hash) {
    return [];
  }
  try {
    const bytes = await storage.get(log.ink_hash);
    const slices = readSliceIndex(bytes);
    return slices.map((slice, i) => ({
      id: slice.id,
      notebook_id: notebookId,
      position: String(i).padStart(10, "0"),
      // Per-page ink lives in the Ready log; companion fetches GET .../ink.
      ink_hash: null,
      ink_bytes: 0,
      created_at: log.created_at,
      updated_at: log.updated_at,
      rev: log.rev,
    }));
  } catch (err) {
    if (err instanceof ZdlFormatError) {
      return null;
    }
    return null;
  }
}

function toPage(row: PageListRow, links: WebPageLink[] = []): WebPage {
  return {
    id: row.id,
    notebook_id: row.notebook_id,
    position: row.position,
    ink_hash: row.ink_hash,
    ink_bytes: row.ink_bytes,
    created_at: row.created_at,
    updated_at: row.updated_at,
    rev: row.rev,
    links,
  };
}

interface LinkRowLite {
  id: string;
  source_id: string;
  target_id: string;
}

/**
 * Resolve notebook + 1-based index/count for each live page id (Android pageRef).
 * Missing / deleted / out-of-scope pages are omitted.
 * Ready logs are the only companion place index (no boards SQL).
 */
export async function placesOf(
  pool: pg.Pool | pg.PoolClient,
  pageIds: string[],
  scope: SyncScope,
  storage?: InkStorage,
): Promise<Map<string, PagePlace>> {
  const out = new Map<string, PagePlace>();
  if (pageIds.length === 0 || !storage) {
    return out;
  }
  const want = new Set(pageIds.map((id) => id.toLowerCase()));
  const { sql, params } = scopeParams(scope);
  const nOwn = sql("notebooks", "n");

  const placeFromPages = (
    pages: PageListRow[],
    notebookKey: string,
    title: string,
  ): void => {
    const index = new Map(pages.map((p, i) => [p.id.toLowerCase(), i]));
    for (const id of want) {
      if (out.has(id)) continue;
      const idx = index.get(id);
      if (idx === undefined) continue;
      out.set(id, {
        page_id: pages[idx].id,
        notebook_id: notebookKey,
        notebook_title: title,
        index: idx + 1,
        count: pages.length,
      });
    }
  };

  const { sql: logSql, params: logParams } = logOwnSql(scope);
  const logs = await pool.query<LogRow>(
    `SELECT l.* FROM notebook_logs l
     WHERE l.deleted_at IS NULL AND l.conflict_of IS NULL AND l.ink_hash IS NOT NULL AND ${logSql}`,
    logParams,
  );
  const titles = new Map<string, string>();
  const logNbIds = [
    ...new Set(logs.rows.map((l) => l.notebook_id).filter((id): id is string => id !== null)),
  ];
  if (logNbIds.length > 0) {
    const more = await pool.query<{ id: string; title: string }>(
      `SELECT n.id, n.title FROM notebooks n
       WHERE n.deleted_at IS NULL AND ${nOwn} AND n.id = ANY($${params.length + 1}::uuid[])`,
      [...params, logNbIds],
    );
    for (const n of more.rows) {
      titles.set(n.id, n.title);
    }
  }
  for (const log of logs.rows) {
    if (out.size >= want.size) break;
    const nb = log.notebook_id;
    if (nb !== null && !titles.has(nb)) continue;
    const pages = await pagesFromReadyLog(storage, log, nb);
    if (!pages) continue;
    placeFromPages(
      pages,
      nb === null ? SCRATCHPAD : nb,
      nb === null ? "Scratchpad" : titles.get(nb)!,
    );
  }
  return out;
}

async function linksForPages(
  pool: pg.Pool | pg.PoolClient,
  pageIds: string[],
  scope: SyncScope,
  storage?: InkStorage,
): Promise<Map<string, WebPageLink[]>> {
  const byPage = new Map<string, WebPageLink[]>();
  if (pageIds.length === 0) {
    return byPage;
  }
  for (const id of pageIds) {
    byPage.set(id, []);
  }

  const { sql, params } = scopeParams(scope);
  // page_links ownership: same row_owners pattern as sync visibility.
  const linkVis =
    scope.kind === "user"
      ? ownedByUser("page_links", "k", 1)
      : `NOT EXISTS (SELECT 1 FROM row_owners o WHERE o.table_name = 'page_links' AND o.row_id = k.id)`;

  const links = await pool.query<LinkRowLite>(
    `SELECT k.id, k.source_id, k.target_id FROM page_links k
     WHERE k.deleted_at IS NULL AND ${linkVis}
       AND (k.source_id = ANY($${params.length + 1}::uuid[])
         OR k.target_id = ANY($${params.length + 1}::uuid[]))`,
    [...params, pageIds],
  );
  if (links.rows.length === 0) {
    return byPage;
  }

  const otherIds = new Set<string>();
  for (const link of links.rows) {
    otherIds.add(link.source_id);
    otherIds.add(link.target_id);
  }
  const places = await placesOf(pool, [...otherIds], scope, storage);

  for (const link of links.rows) {
    if (pageIds.includes(link.source_id)) {
      const place = places.get(link.target_id);
      if (place) {
        byPage.get(link.source_id)!.push({
          id: link.id,
          direction: "out",
          other_id: link.target_id,
          other_notebook_id: place.notebook_id,
          other_notebook_title: place.notebook_title,
          other_index: place.index,
          other_count: place.count,
        });
      }
    }
    if (pageIds.includes(link.target_id)) {
      const place = places.get(link.source_id);
      if (place) {
        byPage.get(link.target_id)!.push({
          id: link.id,
          direction: "in",
          other_id: link.source_id,
          other_notebook_id: place.notebook_id,
          other_notebook_title: place.notebook_title,
          other_index: place.index,
          other_count: place.count,
        });
      }
    }
  }

  // Outgoing first, then backlinks — same order as Android PageSlot.fill.
  for (const [id, list] of byPage) {
    list.sort((a, b) => {
      if (a.direction !== b.direction) {
        return a.direction === "out" ? -1 : 1;
      }
      return a.other_index - b.other_index;
    });
    byPage.set(id, list);
  }
  return byPage;
}

function notebookParam(raw: string): string | null {
  if (raw === SCRATCHPAD) {
    return null;
  }
  if (!UUID.test(raw)) {
    throw new NotFound("notebook not found");
  }
  return raw.toLowerCase();
}

export async function listNotebooks(
  pool: pg.Pool,
  scope: SyncScope,
  storage?: InkStorage,
): Promise<WebNotebook[]> {
  const { sql, params } = scopeParams(scope);
  const nOwn = sql("notebooks", "n");
  const notebooks = await pool.query<NotebookRow>(
    `SELECT n.* FROM notebooks n
     WHERE n.deleted_at IS NULL AND ${nOwn}
     ORDER BY n.position, n.id`,
    params,
  );

  const readyMeta = async (
    notebookId: string | null,
  ): Promise<{ page_count: number; last_edited_at: number | null }> => {
    if (!storage) {
      return { page_count: 0, last_edited_at: null };
    }
    const log = await readyLogForNotebook(pool, notebookId, scope);
    if (!log?.ink_hash) {
      return { page_count: 0, last_edited_at: null };
    }
    const pages = await pagesFromReadyLog(storage, log, notebookId);
    if (!pages) {
      return { page_count: 0, last_edited_at: null };
    }
    return {
      page_count: pages.length,
      last_edited_at: log.updated_at,
    };
  };

  const scratchReady = await readyMeta(null);
  const notebookRows = await Promise.all(
    notebooks.rows.map(async (n) => {
      const ready = await readyMeta(n.id);
      return {
        id: n.id,
        kind: "notebook" as const,
        title: n.title,
        position: n.position,
        parent_id: n.parent_id,
        page_count: ready.page_count,
        last_edited_at: ready.last_edited_at,
        updated_at: n.updated_at,
        rev: n.rev,
      };
    }),
  );

  return [
    {
      id: SCRATCHPAD,
      kind: "scratchpad",
      title: "Scratchpad",
      position: null,
      parent_id: null,
      page_count: scratchReady.page_count,
      last_edited_at: scratchReady.last_edited_at,
      updated_at: null,
      rev: null,
    },
    ...notebookRows,
  ];
}

async function liveNotebook(db: pg.Pool | pg.PoolClient, id: string, scope: SyncScope): Promise<NotebookRow> {
  const { sql, params } = scopeParams(scope);
  const res = await db.query<NotebookRow>(
    `SELECT n.* FROM notebooks n WHERE n.id = $${params.length + 1} AND n.deleted_at IS NULL AND ${sql("notebooks", "n")}`,
    [...params, id],
  );
  if (res.rows.length === 0) {
    throw new NotFound("notebook not found");
  }
  return res.rows[0];
}

/** Live page order for a notebook — Ready {@code .zdl} only. */
async function pagesIn(
  db: pg.Pool | pg.PoolClient,
  notebookId: string | null,
  scope: SyncScope,
  storage?: InkStorage,
): Promise<PageListRow[]> {
  if (!storage) {
    return [];
  }
  const log = await readyLogForNotebook(db, notebookId, scope);
  if (!log) {
    return [];
  }
  const fromReady = await pagesFromReadyLog(storage, log, notebookId);
  return fromReady ?? [];
}

export async function listPages(
  pool: pg.Pool,
  rawNotebookId: string,
  scope: SyncScope,
  storage?: InkStorage,
): Promise<WebPage[]> {
  const notebookId = notebookParam(rawNotebookId);
  if (notebookId !== null) {
    await liveNotebook(pool, notebookId, scope);
  }
  const rows = await pagesIn(pool, notebookId, scope, storage);
  const links = await linksForPages(
    pool,
    rows.map((r) => r.id),
    scope,
    storage,
  );
  return rows.map((row) => toPage(row, links.get(row.id) ?? []));
}

/** Locate a live page for web navigation (page links). */
export async function placeOfPage(
  pool: pg.Pool,
  pageId: string,
  scope: SyncScope,
  storage?: InkStorage,
): Promise<PagePlace> {
  if (!UUID.test(pageId)) {
    throw new NotFound("page not found");
  }
  const places = await placesOf(pool, [pageId.toLowerCase()], scope, storage);
  const place = places.get(pageId.toLowerCase());
  if (!place) {
    throw new NotFound("page not found");
  }
  return place;
}

export interface BackupMissing {
  table: "boards" | "notebook_logs";
  id: string;
  ink_hash: string;
}

/**
 * Whether Hand-off / lost-device restore may unlock.
 *
 * Notebook list on the web can look fine while a notebook_log
 * (the Boox source of truth after v20) is missing from S3 — this check catches that.
 *
 * Uncovered live BOARD ink (no Ready {@code notebook_logs} for the same notebook)
 * must not unlock Hand-off: every inked notebook must already have Ready
 * {@code .zdl} cover. Covered BOARD rows are residue and are excluded from
 * {@link BackupStatus#boards_with_ink}.
 */
export interface BackupStatus {
  ok: boolean;
  ready_for_device_restore: boolean;
  notebooks: number;
  /** Live board ink rows with no covering Ready notebook_log — blocks Hand-off. */
  boards_with_ink: number;
  logs_with_ink: number;
  missing: BackupMissing[];
  note: string;
}

export async function backupStatus(
  pool: pg.Pool,
  storage: InkStorage,
  scope: SyncScope,
): Promise<BackupStatus> {
  const { sql, params } = scopeParams(scope);
  const nOwn = sql("notebooks", "n");
  const bOwn = sql("boards", "b");
  // notebook_logs / row_owners use table_name 'notebook_logs'
  const logOwn =
    scope.kind === "user"
      ? ownedByUser("notebook_logs" as OwnerTable, "l", 1)
      : `NOT EXISTS (SELECT 1 FROM row_owners o WHERE o.table_name = 'notebook_logs' AND o.row_id = l.id)`;
  // Same notebook_id (null = Scratchpad) — Ready .zdl covers BoardEntity .zdi residue.
  const boardCoveredByLog = `EXISTS (
      SELECT 1 FROM notebook_logs l
      WHERE l.deleted_at IS NULL AND l.conflict_of IS NULL AND l.ink_hash IS NOT NULL
        AND ${logOwn}
        AND ((b.notebook_id IS NULL AND l.notebook_id IS NULL)
             OR (b.notebook_id IS NOT NULL AND l.notebook_id = b.notebook_id))
    )`;

  const notebooks = await pool.query<{ n: number }>(
    `SELECT count(*)::int AS n FROM notebooks n WHERE n.deleted_at IS NULL AND ${nOwn}`,
    params,
  );
  const boards = await pool.query<{ id: string; ink_hash: string }>(
    `SELECT b.id, b.ink_hash FROM boards b
     WHERE ${LIVE} AND ${bOwn} AND b.ink_hash IS NOT NULL
       AND NOT ${boardCoveredByLog}`,
    params,
  );
  const logs = await pool.query<{ id: string; ink_hash: string }>(
    `SELECT l.id, l.ink_hash FROM notebook_logs l
     WHERE l.deleted_at IS NULL AND l.conflict_of IS NULL AND l.ink_hash IS NOT NULL AND ${logOwn}`,
    params,
  );

  const missing: BackupMissing[] = [];
  for (const row of boards.rows) {
    if (!(await storage.has(row.ink_hash))) {
      missing.push({ table: "boards", id: row.id, ink_hash: row.ink_hash });
    }
  }
  for (const row of logs.rows) {
    if (!(await storage.has(row.ink_hash))) {
      missing.push({ table: "notebook_logs", id: row.id, ink_hash: row.ink_hash });
    }
  }

  const uncoveredBoards = boards.rows.length;
  const ok = missing.length === 0;
  // Hand-off unlocks only when every inked notebook is Ready-covered and all
  // restore-critical blobs (uncovered boards + logs) are in object storage.
  const ready = ok && uncoveredBoards === 0;
  return {
    ok,
    ready_for_device_restore: ready,
    notebooks: notebooks.rows[0]?.n ?? 0,
    boards_with_ink: uncoveredBoards,
    logs_with_ink: logs.rows.length,
    missing,
    note: ready
      ? "All restore-critical ink blobs are in object storage. Ready notebook logs cover every inked notebook. A fresh signed-in device can pull them."
      : uncoveredBoards > 0
        ? "Uncovered page blob(s) remain — run Prepare transfer on the live Boox so Ready notebook logs cover them before Hand-off."
        : "Some restore-critical ink hashes are in Postgres but missing from object storage. Do not uninstall the Boox until sync re-uploads them.",
  };
}

/** Serve Ready {@code .zdl} bytes for client canvas replay. */
async function notebookInkBytes(
  pool: pg.Pool,
  rawNotebookId: string,
  scope: SyncScope,
  storage: InkStorage,
): Promise<Buffer> {
  const notebookId = rawNotebookId === SCRATCHPAD ? null : rawNotebookId;
  if (notebookId !== null) {
    if (!UUID.test(notebookId)) {
      throw new NotFound("notebook not found");
    }
    await liveNotebook(pool, notebookId, scope);
  }
  const log = await readyLogForNotebook(pool, notebookId, scope);
  if (!log?.ink_hash) {
    throw new NotFound("ink log not found");
  }
  try {
    return await storage.get(log.ink_hash);
  } catch {
    throw new NotFound("ink log blob missing");
  }
}

export function registerWebRoutes(
  app: FastifyInstance,
  pool: pg.Pool,
  scopeOf: (req: { principal?: import("./auth.js").AuthPrincipal }) => SyncScope,
  storage?: InkStorage,
): void {
  app.get("/web/notebooks", async (req) => ({
    notebooks: await listNotebooks(pool, scopeOf(req), storage),
  }));

  app.get<{ Params: { id: string } }>("/web/notebooks/:id/pages", async (req) => ({
    notebook_id: req.params.id,
    pages: await listPages(pool, req.params.id, scopeOf(req), storage),
    ink_url: `/web/notebooks/${req.params.id}/ink`,
  }));

  app.get<{ Params: { id: string } }>("/web/pages/:id/place", async (req) =>
    placeOfPage(pool, req.params.id, scopeOf(req), storage),
  );

  if (storage) {
    app.get<{ Params: { id: string } }>("/web/notebooks/:id/ink", async (req, reply) => {
      const bytes = await notebookInkBytes(pool, req.params.id, scopeOf(req), storage);
      return reply
        .header("content-type", "application/octet-stream")
        .header("cache-control", "private, max-age=60")
        .send(bytes);
    });

    app.get("/web/backup-status", async (req) => backupStatus(pool, storage, scopeOf(req)));

    app.get("/web/writer", async (req) => {
      const scope = scopeOf(req);
      if (scope.kind !== "user") {
        throw new Forbidden("Google sign-in required");
      }
      const backup = await backupStatus(pool, storage, scope);
      return getWriterStatus(pool, scope.userId, backup.ready_for_device_restore);
    });

    app.post("/web/writer/hand-off", async (req) => {
      const scope = scopeOf(req);
      if (scope.kind !== "user") {
        throw new Forbidden("Google sign-in required");
      }
      const backup = await backupStatus(pool, storage, scope);
      try {
        return await handOff(pool, scope.userId, backup.ready_for_device_restore);
      } catch (err) {
        throw new BadRequest(err instanceof Error ? err.message : "Hand off failed");
      }
    });

    app.post("/web/writer/lost-device", async (req) => {
      const scope = scopeOf(req);
      if (scope.kind !== "user") {
        throw new Forbidden("Google sign-in required");
      }
      const backup = await backupStatus(pool, storage, scope);
      try {
        return await ackLostDevice(pool, scope.userId, backup.ready_for_device_restore);
      } catch (err) {
        throw new BadRequest(err instanceof Error ? err.message : "Lost-device confirm failed");
      }
    });

    app.post("/web/writer/cancel", async (req) => {
      const scope = scopeOf(req);
      if (scope.kind !== "user") {
        throw new Forbidden("Google sign-in required");
      }
      const backup = await backupStatus(pool, storage, scope);
      return cancelHandOff(pool, scope.userId, backup.ready_for_device_restore);
    });
  }

  app.patch("/web/notebooks/:id", async (_req, reply) =>
    reply.code(405).send({ error: "read_only", message: "The web companion cannot rename notebooks." }),
  );

  app.post("/web/pages/:id/move", async (_req, reply) =>
    reply.code(405).send({ error: "read_only", message: "The web companion cannot move or reorder pages." }),
  );
}
