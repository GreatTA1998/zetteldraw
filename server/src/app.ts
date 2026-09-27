import { timingSafeEqual } from "node:crypto";
import Fastify, { type FastifyInstance } from "fastify";
import type pg from "pg";
import type { InkStorage } from "./storage.js";
import { pull, push } from "./sync.js";
import type { Thumbnailer } from "./thumbs.js";
import { BadRequest, parsePushBody } from "./validate.js";
import { NotFound, registerWebRoutes } from "./web.js";

export const SCHEMA_HEADER = "x-zetteldraw-schema";

export interface AppDeps {
  pool: pg.Pool;
  storage: InkStorage;
  schemaVersion: number;
  deviceTokens: string[];
  /** Renders page images; enables the /web/* endpoints and thumbnails on push. */
  thumbs?: Thumbnailer;
  logger?: boolean;
}

function tokenMatches(presented: string, tokens: string[]): boolean {
  const a = Buffer.from(presented);
  let ok = false;
  for (const token of tokens) {
    const b = Buffer.from(token);
    if (a.length === b.length && timingSafeEqual(a, b)) {
      ok = true;
    }
  }
  return ok;
}

export function buildApp(deps: AppDeps): FastifyInstance {
  const app = Fastify({ logger: deps.logger ?? true, bodyLimit: 64 * 1024 * 1024 });

  app.get("/healthz", async () => ({ ok: true, schema_version: deps.schemaVersion }));

  app.addHook("onRequest", async (req, reply) => {
    const isSync = req.url.startsWith("/sync/");
    if (!isSync && !req.url.startsWith("/web/")) {
      return;
    }
    const auth = req.headers.authorization ?? "";
    const token = auth.startsWith("Bearer ") ? auth.slice(7) : "";
    if (!token || !tokenMatches(token, deps.deviceTokens)) {
      return reply.code(401).send({ error: "unauthorized" });
    }
    // The web overview reads through its own endpoints and is not bound to a Room schema.
    if (!isSync) {
      return;
    }
    const clientSchema = Number(req.headers[SCHEMA_HEADER]);
    if (clientSchema !== deps.schemaVersion) {
      return reply
        .code(409)
        .send({ error: "schema_mismatch", server_schema: deps.schemaVersion, client_schema: clientSchema || null });
    }
  });

  app.setErrorHandler((err, _req, reply) => {
    if (err instanceof NotFound) {
      return reply.code(404).send({ error: "not_found", message: err.message });
    }
    if (err instanceof BadRequest) {
      return reply.code(400).send({ error: "bad_request", message: err.message });
    }
    const status = (err as { statusCode?: number }).statusCode;
    if (status && status >= 400 && status < 500) {
      return reply.code(status).send({ error: "bad_request", message: (err as Error).message });
    }
    app.log.error(err);
    return reply.code(500).send({ error: "internal" });
  });

  app.post("/sync/push", async (req) => {
    const body = parsePushBody(req.body);
    if (body.schema_version !== deps.schemaVersion) {
      throw new BadRequest(`schema_version ${body.schema_version} != ${deps.schemaVersion}`);
    }
    const results = await push(deps.pool, deps.storage, body);
    if (deps.thumbs) {
      const stored = new Set(results.filter((r) => r.entity === "board" && r.status !== "missing_blob").map((r) => r.id));
      for (const board of body.boards) {
        if (board.ink_hash && board.deleted_at === null && stored.has(board.id)) {
          deps.thumbs.enqueue(board.ink_hash);
        }
      }
    }
    req.log.info(
      { device: body.device_id, notebooks: body.notebooks.length, boards: body.boards.length },
      "push",
    );
    return { schema_version: deps.schemaVersion, results };
  });

  app.get<{ Querystring: { since?: string; limit?: string } }>("/sync/pull", async (req) => {
    const since = Number(req.query.since ?? 0);
    const limit = Math.min(500, Math.max(1, Number(req.query.limit ?? 200)));
    if (!Number.isSafeInteger(since) || since < 0 || !Number.isSafeInteger(limit)) {
      throw new BadRequest("since and limit must be non-negative integers");
    }
    return pull(deps.pool, deps.storage, deps.schemaVersion, since, limit);
  });

  if (deps.thumbs) {
    app.get("/web/session", async () => ({ ok: true, schema_version: deps.schemaVersion }));
    registerWebRoutes(app, deps.pool, deps.thumbs);
  }

  return app;
}
