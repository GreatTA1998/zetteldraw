import type { FastifyInstance, FastifyRequest } from "fastify";
import Fastify from "fastify";
import type pg from "pg";
import {
  claimUnownedLibrary,
  getLibraryClaimer,
  upsertUser,
  type ClaimResult,
} from "./accounts.js";
import { AuthService, issueAccessToken, type AuthPrincipal } from "./auth.js";
import type { InkStorage } from "./storage.js";
import { pull, push, type SyncScope } from "./sync.js";
import type { Thumbnailer } from "./thumbs.js";
import { BadRequest, parsePushBody } from "./validate.js";
import { Forbidden, NotFound, registerWebRoutes } from "./web.js";

export const SCHEMA_HEADER = "x-zetteldraw-schema";

declare module "fastify" {
  interface FastifyRequest {
    principal?: AuthPrincipal;
  }
}

export interface AppDeps {
  pool: pg.Pool;
  storage: InkStorage;
  schemaVersion: number;
  auth: AuthService;
  sessionSecret: string | undefined;
  /** Renders page images; enables the /web/* endpoints and thumbnails on push. */
  thumbs?: Thumbnailer;
  logger?: boolean;
}

function scopeOf(principal: AuthPrincipal | undefined): SyncScope {
  if (principal?.kind === "google") {
    return { kind: "user", userId: principal.user.id };
  }
  return { kind: "unowned" };
}

export function buildApp(deps: AppDeps): FastifyInstance {
  const app = Fastify({ logger: deps.logger ?? true, bodyLimit: 64 * 1024 * 1024 });

  app.get("/healthz", async () => ({ ok: true, schema_version: deps.schemaVersion }));

  app.post<{ Body: { id_token?: unknown; claim?: unknown } }>("/auth/google", async (req, reply) => {
    const idToken = typeof req.body?.id_token === "string" ? req.body.id_token.trim() : "";
    if (!idToken) {
      throw new BadRequest("id_token is required");
    }
    const user = await deps.auth.verifyGoogleIdToken(idToken);
    if (!user) {
      return reply.code(401).send({ error: "unauthorized", message: "invalid Google ID token" });
    }
    await upsertUser(deps.pool, user);
    const shouldClaim = req.body?.claim !== false;
    let claim: ClaimResult | null = null;
    if (shouldClaim) {
      claim = await claimUnownedLibrary(deps.pool, user);
    }
    if (!deps.sessionSecret) {
      return {
        user: { id: user.id, email: user.email },
        access_token: idToken,
        expires_in: 3600,
        token_type: "Bearer",
        claim,
      };
    }
    const issued = issueAccessToken(user, deps.sessionSecret);
    return {
      user: { id: user.id, email: user.email },
      ...issued,
      token_type: "Bearer",
      claim,
    };
  });

  app.post("/auth/claim", async (req, reply) => {
    if (req.principal?.kind !== "google") {
      return reply.code(401).send({ error: "unauthorized" });
    }
    const claim = await claimUnownedLibrary(deps.pool, req.principal.user);
    return { claim, user: { id: req.principal.user.id, email: req.principal.user.email } };
  });

  app.addHook("onRequest", async (req, reply) => {
    const url = req.url.split("?")[0] ?? req.url;
    const needsAuth =
      url.startsWith("/sync/") ||
      url.startsWith("/web/") ||
      url === "/auth/claim";
    if (!needsAuth) {
      return;
    }
    const claimedBy = await getLibraryClaimer(deps.pool);
    const principal = await deps.auth.resolveBearer(req.headers.authorization, {
      libraryClaimed: claimedBy !== null,
    });
    if (!principal) {
      return reply.code(401).send({ error: "unauthorized" });
    }
    req.principal = principal;

    if (!url.startsWith("/sync/")) {
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
    if (err instanceof Forbidden) {
      return reply.code(403).send({ error: "forbidden", message: err.message });
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
    const scope = scopeOf(req.principal);
    const results = await push(deps.pool, deps.storage, body, scope);
    if (deps.thumbs) {
      const stored = new Set(results.filter((r) => r.entity === "board" && r.status !== "missing_blob").map((r) => r.id));
      for (const board of body.boards) {
        if (board.ink_hash && board.deleted_at === null && stored.has(board.id)) {
          deps.thumbs.enqueue(board.ink_hash);
        }
      }
    }
    req.log.info(
      {
        device: body.device_id,
        user: req.principal?.kind === "google" ? req.principal.user.id : "device",
        notebooks: body.notebooks.length,
        boards: body.boards.length,
        logs: body.logs.length,
        links: body.links.length,
      },
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
    return pull(deps.pool, deps.storage, deps.schemaVersion, since, limit, scopeOf(req.principal));
  });

  if (deps.thumbs) {
    app.get("/web/session", async (req) => ({
      ok: true,
      schema_version: deps.schemaVersion,
      auth:
        req.principal?.kind === "google"
          ? { kind: "google", user: { id: req.principal.user.id, email: req.principal.user.email } }
          : { kind: "device" },
    }));
    registerWebRoutes(app, deps.pool, deps.thumbs, (req) => scopeOf(req.principal));
  }

  return app;
}

/** Helper for tests that build a bare FastifyRequest-like principal. */
export function principalScope(req: FastifyRequest): SyncScope {
  return scopeOf(req.principal);
}
