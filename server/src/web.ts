import type { FastifyInstance } from "fastify";
import type pg from "pg";
import { InkFormatError } from "./ink.js";
import type { BoardRow, NotebookRow } from "./lww.js";
import { planMove, type MoveTarget } from "./order.js";
import { PUSH_LOCK } from "./sync.js";
import type { ImageKind, Thumbnailer } from "./thumbs.js";
import { BadRequest } from "./validate.js";

/**
 * Read endpoints and small edits for the web overview. Edits are ordinary row
 * writes with a fresh rev and a newer updated_at, taken under the push lock, so
 * the Boox pulls them like any other change and LWW treats them like a device edit.
 */

export const SCRATCHPAD = "scratchpad";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const IMAGE_FILE = /^([0-9a-f]{64})\.png$/;
const MAX_TITLE = 200;

export class NotFound extends Error {}

export interface WebNotebook {
  id: string;
  kind: "scratchpad" | "notebook";
  title: string;
  position: string | null;
  page_count: number;
  last_edited_at: number | null;
  updated_at: number | null;
  rev: number | null;
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
}

const LIVE = "b.deleted_at IS NULL AND b.conflict_of IS NULL";
/** Pages whose notebook is missing or deleted show in the scratchpad, as they do on the device. */
const IN_SCRATCHPAD = `(b.notebook_id IS NULL OR NOT EXISTS (
  SELECT 1 FROM notebooks n WHERE n.id = b.notebook_id AND n.deleted_at IS NULL))`;

function toPage(row: BoardRow): WebPage {
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
  };
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

export async function listNotebooks(pool: pg.Pool): Promise<WebNotebook[]> {
  const scratch = await pool.query<{ page_count: number; last_edited_at: number | null }>(
    `SELECT count(*)::int AS page_count, max(b.updated_at) AS last_edited_at
     FROM boards b WHERE ${LIVE} AND ${IN_SCRATCHPAD}`,
  );
  const notebooks = await pool.query<NotebookRow & { page_count: number; last_edited_at: number | null }>(
    `SELECT n.*, count(b.id)::int AS page_count, max(b.updated_at) AS last_edited_at
     FROM notebooks n LEFT JOIN boards b ON b.notebook_id = n.id AND ${LIVE}
     WHERE n.deleted_at IS NULL
     GROUP BY n.id
     ORDER BY n.position, n.id`,
  );
  return [
    {
      id: SCRATCHPAD,
      kind: "scratchpad",
      title: "Scratchpad",
      position: null,
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
      page_count: n.page_count,
      last_edited_at: n.last_edited_at,
      updated_at: n.updated_at,
      rev: n.rev,
    })),
  ];
}

async function liveNotebook(db: pg.Pool | pg.PoolClient, id: string, lock = false): Promise<NotebookRow> {
  const res = await db.query<NotebookRow>(
    `SELECT * FROM notebooks WHERE id = $1 AND deleted_at IS NULL${lock ? " FOR UPDATE" : ""}`,
    [id],
  );
  if (res.rows.length === 0) {
    throw new NotFound("notebook not found");
  }
  return res.rows[0];
}

async function pagesIn(db: pg.Pool | pg.PoolClient, notebookId: string | null): Promise<BoardRow[]> {
  const res =
    notebookId === null
      ? await db.query<BoardRow>(`SELECT b.* FROM boards b WHERE ${LIVE} AND ${IN_SCRATCHPAD} ORDER BY b.position, b.id`)
      : await db.query<BoardRow>(`SELECT b.* FROM boards b WHERE ${LIVE} AND b.notebook_id = $1 ORDER BY b.position, b.id`, [
          notebookId,
        ]);
  return res.rows;
}

export async function listPages(pool: pg.Pool, rawNotebookId: string): Promise<WebPage[]> {
  const notebookId = notebookParam(rawNotebookId);
  if (notebookId !== null) {
    await liveNotebook(pool, notebookId);
  }
  return (await pagesIn(pool, notebookId)).map(toPage);
}

async function inPushLock<T>(pool: pg.Pool, fn: (client: pg.PoolClient) => Promise<T>): Promise<T> {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    await client.query("SELECT pg_advisory_xact_lock($1)", [PUSH_LOCK]);
    const result = await fn(client);
    await client.query("COMMIT");
    return result;
  } catch (err) {
    await client.query("ROLLBACK").catch(() => {});
    throw err;
  } finally {
    client.release();
  }
}

export async function renameNotebook(pool: pg.Pool, rawId: string, rawTitle: unknown, now = Date.now()) {
  const id = notebookParam(rawId);
  if (id === null) {
    throw new BadRequest("the scratchpad cannot be renamed");
  }
  const title = typeof rawTitle === "string" ? rawTitle.trim() : "";
  if (title.length === 0 || title.length > MAX_TITLE) {
    throw new BadRequest(`title must be 1-${MAX_TITLE} characters`);
  }
  return inPushLock(pool, async (client) => {
    const existing = await liveNotebook(client, id, true);
    if (existing.title === title) {
      return existing;
    }
    const res = await client.query<NotebookRow>(
      `UPDATE notebooks SET title = $2, updated_at = GREATEST($3::bigint, updated_at + 1), rev = nextval('sync_rev')
       WHERE id = $1 RETURNING *`,
      [id, title, now],
    );
    return res.rows[0];
  });
}

export interface MoveRequest {
  notebookId: string | null;
  target: MoveTarget;
}

export function parseMoveBody(raw: unknown): MoveRequest {
  if (typeof raw !== "object" || raw === null || Array.isArray(raw)) {
    throw new BadRequest("body must be an object");
  }
  const body = raw as Record<string, unknown>;
  const nb = body.notebook_id;
  let notebookId: string | null;
  if (nb === null || nb === SCRATCHPAD) {
    notebookId = null;
  } else if (typeof nb === "string" && UUID.test(nb)) {
    notebookId = nb.toLowerCase();
  } else {
    throw new BadRequest("notebook_id must be a notebook id, \"scratchpad\" or null");
  }
  let target: MoveTarget;
  if (!("after_id" in body) || body.after_id === undefined) {
    target = { kind: "end" };
  } else if (body.after_id === null) {
    target = { kind: "start" };
  } else if (typeof body.after_id === "string" && UUID.test(body.after_id)) {
    target = { kind: "after", id: body.after_id.toLowerCase() };
  } else {
    throw new BadRequest("after_id must be a page id, null (first) or omitted (last)");
  }
  return { notebookId, target };
}

export async function movePage(pool: pg.Pool, rawPageId: string, move: MoveRequest, now = Date.now()) {
  if (!UUID.test(rawPageId)) {
    throw new NotFound("page not found");
  }
  const pageId = rawPageId.toLowerCase();
  return inPushLock(pool, async (client) => {
    const page = (
      await client.query<BoardRow>(`SELECT * FROM boards b WHERE b.id = $1 AND ${LIVE} FOR UPDATE`, [pageId])
    ).rows[0];
    if (!page) {
      throw new NotFound("page not found");
    }
    if (move.notebookId !== null) {
      await liveNotebook(client, move.notebookId);
    }
    const targetPages = await pagesIn(client, move.notebookId);
    const inTarget = targetPages.some((p) => p.id === pageId);
    const plan = planMove(targetPages, { ...page, inTarget }, move.target);
    if (plan.kind === "bad_anchor") {
      throw new BadRequest("after_id is not a live page in the target notebook");
    }
    if (plan.kind === "noop" && page.notebook_id === move.notebookId) {
      return { status: "unchanged" as const, page: toPage(page) };
    }
    const position = plan.kind === "write" ? plan.position : page.position;
    const res = await client.query<BoardRow>(
      `UPDATE boards SET notebook_id = $2, position = $3,
         updated_at = GREATEST($4::bigint, updated_at + 1), rev = nextval('sync_rev')
       WHERE id = $1 RETURNING *`,
      [pageId, move.notebookId, position, now],
    );
    return { status: "moved" as const, page: toPage(res.rows[0]) };
  });
}

export function registerWebRoutes(app: FastifyInstance, pool: pg.Pool, thumbs: Thumbnailer): void {
  app.get("/web/notebooks", async () => ({ notebooks: await listNotebooks(pool) }));

  app.get<{ Params: { id: string } }>("/web/notebooks/:id/pages", async (req) => ({
    notebook_id: req.params.id,
    pages: await listPages(pool, req.params.id),
  }));

  app.patch<{ Params: { id: string }; Body: { title?: unknown } }>("/web/notebooks/:id", async (req) => {
    const row = await renameNotebook(pool, req.params.id, req.body?.title);
    return { notebook: { id: row.id, title: row.title, updated_at: row.updated_at, rev: row.rev } };
  });

  app.post<{ Params: { id: string } }>("/web/pages/:id/move", async (req) =>
    movePage(pool, req.params.id, parseMoveBody(req.body)),
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
