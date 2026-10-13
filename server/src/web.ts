import type { FastifyInstance } from "fastify";
import type pg from "pg";
import { isUnowned, ownedByUser, type OwnerTable } from "./accounts.js";
import { InkFormatError } from "./ink.js";
import type { BoardRow, NotebookRow } from "./lww.js";
import type { SyncScope } from "./sync.js";
import type { InkStorage } from "./storage.js";
import type { ImageKind, Thumbnailer } from "./thumbs.js";
import { BadRequest } from "./validate.js";
import { ackLostDevice, cancelHandOff, getWriterStatus, handOff } from "./writer.js";
/**
 * Read endpoints for the web overview. Notebook/page mutations are refused —
 * the companion is strictly read-only for ink. The only write it offers is
 * Hand off / lost-device ack (live-writer lease), which does not touch notebook data.
 */

export const SCRATCHPAD = "scratchpad";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const IMAGE_FILE = /^([0-9a-f]{64})\.png$/;

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
  thumb_url: string | null;
  render_url: string | null;
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

function toPage(row: BoardRow, links: WebPageLink[] = []): WebPage {
  return {
    id: row.id,
    notebook_id: row.notebook_id,
    position: row.position,
    ink_hash: row.ink_hash,
    ink_bytes: row.ink_bytes,
    created_at: row.created_at,
    updated_at: row.updated_at,
    rev: row.rev,
    thumb_url: row.ink_hash ? `/web/thumbs/${row.ink_hash}.png` : null,
    render_url: row.ink_hash ? `/web/renders/${row.ink_hash}.png` : null,
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
 */
export async function placesOf(
  pool: pg.Pool | pg.PoolClient,
  pageIds: string[],
  scope: SyncScope,
): Promise<Map<string, PagePlace>> {
  const out = new Map<string, PagePlace>();
  if (pageIds.length === 0) {
    return out;
  }
  const { sql, params } = scopeParams(scope);
  const bOwn = sql("boards", "b");
  const nOwn = sql("notebooks", "n");
  const boards = await pool.query<BoardRow>(
    `SELECT b.* FROM boards b
     WHERE ${LIVE} AND ${bOwn} AND b.id = ANY($${params.length + 1}::uuid[])`,
    [...params, pageIds],
  );
  if (boards.rows.length === 0) {
    return out;
  }

  const notebookIds = [
    ...new Set(boards.rows.map((b) => b.notebook_id).filter((id): id is string => id !== null)),
  ];
  const titles = new Map<string, string>();
  if (notebookIds.length > 0) {
    const notebooks = await pool.query<{ id: string; title: string }>(
      `SELECT n.id, n.title FROM notebooks n
       WHERE n.deleted_at IS NULL AND ${nOwn} AND n.id = ANY($${params.length + 1}::uuid[])`,
      [...params, notebookIds],
    );
    for (const n of notebooks.rows) {
      titles.set(n.id, n.title);
    }
  }

  // Pages whose notebook is missing/deleted/out of scope live in Scratchpad (same as listPages).
  const inScratchpad = new Set<string>();
  for (const row of boards.rows) {
    if (row.notebook_id === null || !titles.has(row.notebook_id)) {
      inScratchpad.add(row.id);
    }
  }

  const scratchPages =
    inScratchpad.size > 0 ? await pagesIn(pool, null, scope) : [];
  const scratchIndex = new Map(scratchPages.map((p, i) => [p.id, i]));

  const byNotebook = new Map<string, BoardRow[]>();
  for (const row of boards.rows) {
    if (inScratchpad.has(row.id)) {
      const idx = scratchIndex.get(row.id);
      if (idx === undefined) continue;
      out.set(row.id, {
        page_id: row.id,
        notebook_id: SCRATCHPAD,
        notebook_title: "Scratchpad",
        index: idx + 1,
        count: scratchPages.length,
      });
      continue;
    }
    const nb = row.notebook_id!;
    const list = byNotebook.get(nb) ?? [];
    list.push(row);
    byNotebook.set(nb, list);
  }

  for (const [notebookId, needed] of byNotebook) {
    const all = await pagesIn(pool, notebookId, scope);
    const index = new Map(all.map((p, i) => [p.id, i]));
    const title = titles.get(notebookId) ?? "";
    for (const row of needed) {
      const idx = index.get(row.id);
      if (idx === undefined || title === "") continue;
      out.set(row.id, {
        page_id: row.id,
        notebook_id: notebookId,
        notebook_title: title,
        index: idx + 1,
        count: all.length,
      });
    }
  }
  return out;
}

async function linksForPages(
  pool: pg.Pool | pg.PoolClient,
  pageIds: string[],
  scope: SyncScope,
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
  const places = await placesOf(pool, [...otherIds], scope);

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

export async function listNotebooks(pool: pg.Pool, scope: SyncScope): Promise<WebNotebook[]> {
  const { sql, params } = scopeParams(scope);
  const nOwn = sql("notebooks", "n");
  const bOwn = sql("boards", "b");
  // Pages whose notebook is missing/deleted/out of scope show in the scratchpad.
  const inScratchpad = `(b.notebook_id IS NULL OR NOT EXISTS (
    SELECT 1 FROM notebooks n WHERE n.id = b.notebook_id AND n.deleted_at IS NULL AND ${nOwn}))`;

  const scratch = await pool.query<{ page_count: number; last_edited_at: number | null }>(
    `SELECT count(*)::int AS page_count, max(b.updated_at) AS last_edited_at
     FROM boards b WHERE ${LIVE} AND ${bOwn} AND ${inScratchpad}`,
    params,
  );
  const notebooks = await pool.query<NotebookRow & { page_count: number; last_edited_at: number | null }>(
    `SELECT n.*, count(b.id)::int AS page_count, max(b.updated_at) AS last_edited_at
     FROM notebooks n
     LEFT JOIN boards b ON b.notebook_id = n.id AND ${LIVE} AND ${bOwn}
     WHERE n.deleted_at IS NULL AND ${nOwn}
     GROUP BY n.id
     ORDER BY n.position, n.id`,
    params,
  );
  return [
    {
      id: SCRATCHPAD,
      kind: "scratchpad",
      title: "Scratchpad",
      position: null,
      parent_id: null,
      page_count: scratch.rows[0].page_count,
      last_edited_at: scratch.rows[0].last_edited_at,
      updated_at: null,
      rev: null,
    },
    ...notebooks.rows.map((n) => ({
      id: n.id,
      kind: "notebook" as const,
      title: n.title,
      position: n.position,
      parent_id: n.parent_id,
      page_count: n.page_count,
      last_edited_at: n.last_edited_at,
      updated_at: n.updated_at,
      rev: n.rev,
    })),
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

async function pagesIn(db: pg.Pool | pg.PoolClient, notebookId: string | null, scope: SyncScope): Promise<BoardRow[]> {
  const { sql, params } = scopeParams(scope);
  const bOwn = sql("boards", "b");
  const nOwn = sql("notebooks", "n");
  if (notebookId === null) {
    const inScratchpad = `(b.notebook_id IS NULL OR NOT EXISTS (
      SELECT 1 FROM notebooks n WHERE n.id = b.notebook_id AND n.deleted_at IS NULL AND ${nOwn}))`;
    const res = await db.query<BoardRow>(
      `SELECT b.* FROM boards b WHERE ${LIVE} AND ${bOwn} AND ${inScratchpad} ORDER BY b.position, b.id`,
      params,
    );
    return res.rows;
  }
  const res = await db.query<BoardRow>(
    `SELECT b.* FROM boards b WHERE ${LIVE} AND ${bOwn} AND b.notebook_id = $${params.length + 1} ORDER BY b.position, b.id`,
    [...params, notebookId],
  );
  return res.rows;
}

export async function listPages(pool: pg.Pool, rawNotebookId: string, scope: SyncScope): Promise<WebPage[]> {
  const notebookId = notebookParam(rawNotebookId);
  if (notebookId !== null) {
    await liveNotebook(pool, notebookId, scope);
  }
  const rows = await pagesIn(pool, notebookId, scope);
  const links = await linksForPages(
    pool,
    rows.map((r) => r.id),
    scope,
  );
  return rows.map((row) => toPage(row, links.get(row.id) ?? []));
}

/** Locate a live page for web navigation (page links). */
export async function placeOfPage(
  pool: pg.Pool,
  pageId: string,
  scope: SyncScope,
): Promise<PagePlace> {
  if (!UUID.test(pageId)) {
    throw new NotFound("page not found");
  }
  const places = await placesOf(pool, [pageId.toLowerCase()], scope);
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
 * Whether every owned live ink blob is present in object storage.
 * Notebook list / page thumbs on the web can look fine while a notebook_log
 * (the Boox source of truth after v20) is missing from S3 — this check catches that.
 */
export interface BackupStatus {
  ok: boolean;
  ready_for_device_restore: boolean;
  notebooks: number;
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

  const notebooks = await pool.query<{ n: number }>(
    `SELECT count(*)::int AS n FROM notebooks n WHERE n.deleted_at IS NULL AND ${nOwn}`,
    params,
  );
  const boards = await pool.query<{ id: string; ink_hash: string }>(
    `SELECT b.id, b.ink_hash FROM boards b
     WHERE ${LIVE} AND ${bOwn} AND b.ink_hash IS NOT NULL`,
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

  const ok = missing.length === 0;
  return {
    ok,
    ready_for_device_restore: ok,
    notebooks: notebooks.rows[0]?.n ?? 0,
    boards_with_ink: boards.rows.length,
    logs_with_ink: logs.rows.length,
    missing,
    note: ok
      ? "All owned ink blobs are in object storage. A fresh signed-in device can pull them."
      : "Some ink hashes are in Postgres but missing from object storage. Do not uninstall the Boox until sync re-uploads them.",
  };
}

export function registerWebRoutes(
  app: FastifyInstance,
  pool: pg.Pool,
  thumbs: Thumbnailer,
  scopeOf: (req: { principal?: import("./auth.js").AuthPrincipal }) => SyncScope,
  storage?: InkStorage,
): void {
  app.get("/web/notebooks", async (req) => ({ notebooks: await listNotebooks(pool, scopeOf(req)) }));

  app.get<{ Params: { id: string } }>("/web/notebooks/:id/pages", async (req) => ({
    notebook_id: req.params.id,
    pages: await listPages(pool, req.params.id, scopeOf(req)),
  }));

  app.get<{ Params: { id: string } }>("/web/pages/:id/place", async (req) =>
    placeOfPage(pool, req.params.id, scopeOf(req)),
  );

  if (storage) {
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

  const image = (kind: ImageKind) =>
    async (req: { params: { file: string } }, reply: import("fastify").FastifyReply) => {
      const match = IMAGE_FILE.exec(req.params.file);
      if (!match) {
        throw new NotFound("image not found");
      }
      let png: Buffer | null;
      try {
        png = await thumbs.image(kind, match[1]);
      } catch (err) {
        if (err instanceof InkFormatError) {
          return reply.code(422).send({ error: "unreadable_ink", message: err.message });
        }
        throw err;
      }
      if (!png) {
        throw new NotFound("ink not found");
      }
      return reply
        .header("content-type", "image/png")
        .header("cache-control", "private, max-age=31536000, immutable")
        .send(png);
    };
  app.get<{ Params: { file: string } }>("/web/thumbs/:file", image("thumb"));
  app.get<{ Params: { file: string } }>("/web/renders/:file", image("render"));
}
