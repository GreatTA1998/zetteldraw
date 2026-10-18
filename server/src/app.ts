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
import { logHeads, pull, push, sha256Hex, type SyncScope } from "./sync.js";
import type { Thumbnailer } from "./thumbs.js";
import { BadRequest, parsePushBody } from "./validate.js";
import { Forbidden, NotFound, registerWebRoutes } from "./web.js";
import { backupStatus } from "./web.js";
import { assertCanPush, claimWriter, NotLiveWriter, prepareTransfer } from "./writer.js";

export const SCHEMA_HEADER = "x-zetteldraw-schema";

const SHA256_HEX = /^[0-9a-f]{64}$/i;
/** Raw ink uploads (Scratchpad logs can exceed the old 64MB JSON Base64 budget). */
const BLOB_BODY_LIMIT = 256 * 1024 * 1024;

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
  // JSON push stays modest (large ink uses /sync/blob). Octet uploads allow big notebook logs.
  const app = Fastify({ logger: deps.logger ?? true, bodyLimit: BLOB_BODY_LIMIT });

  app.addContentTypeParser(
    "application/octet-stream",
    { parseAs: "buffer", bodyLimit: BLOB_BODY_LIMIT },
    (_req, body, done) => {
      done(null, body);
    },
  );

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
    // onRequest still runs for unmatched paths. Skip auth so missing routes
    // stay 404 (not 401) — tip-ship smoke treats 404 as "route not deployed"
    // and unauthenticated 401 as "route registered".
    if (!req.routeOptions?.url) {
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
    if (err instanceof NotLiveWriter) {
      return reply.code(403).send({ error: "not_live_writer", message: err.message });
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

  app.post<{ Body: { device_id?: unknown; device_label?: unknown } }>("/sync/claim-writer", async (req) => {
    if (req.principal?.kind !== "google") {
      throw new Forbidden("Google sign-in required to claim the live writer");
    }
    const deviceId = typeof req.body?.device_id === "string" ? req.body.device_id.trim() : "";
    const deviceLabel = typeof req.body?.device_label === "string" ? req.body.device_label.trim() : "";
    if (!deviceId) {
      throw new BadRequest("device_id is required");
    }
    const result = await claimWriter(deps.pool, req.principal.user.id, deviceId, deviceLabel);
    return { ok: true, ...result };
  });

  /** Live Boox: drain outbox on device, then call this to unlock web Hand off. */
  app.post<{ Body: { device_id?: unknown; device_label?: unknown } }>("/sync/prepare-transfer", async (req) => {
    if (req.principal?.kind !== "google") {
      throw new Forbidden("Google sign-in required to Prepare transfer");
    }
    const deviceId = typeof req.body?.device_id === "string" ? req.body.device_id.trim() : "";
    const deviceLabel = typeof req.body?.device_label === "string" ? req.body.device_label.trim() : "";
    if (!deviceId) {
      throw new BadRequest("device_id is required");
    }
    const scope = scopeOf(req.principal);
    const backup = await backupStatus(deps.pool, deps.storage, scope);
    try {
      const status = await prepareTransfer(
        deps.pool,
        req.principal.user.id,
        deviceId,
        deviceLabel,
        backup.ready_for_device_restore,
      );
      return { ok: true, ...status };
    } catch (err) {
      if (err instanceof NotLiveWriter) {
        throw err;
      }
      throw new BadRequest(err instanceof Error ? err.message : "Prepare transfer failed");
    }
  });

  /**
   * Raw ink blob upload. Prefer this over Base64-in-JSON for large notebook logs
   * so Boox Prepare transfer does not allocate ~4/3 N UTF-16 / JSON copies.
   * Query: device_id (required for Google live-writer gate).
   */
  app.put<{ Params: { hash: string }; Querystring: { device_id?: string } }>(
    "/sync/blob/:hash",
    async (req) => {
      const hash = (req.params.hash || "").toLowerCase();
      if (!SHA256_HEX.test(hash)) {
        throw new BadRequest("hash must be a sha256 hex digest");
      }
      const deviceId = typeof req.query.device_id === "string" ? req.query.device_id.trim() : "";
      const scope = scopeOf(req.principal);
      await assertCanPush(deps.pool, scope, deviceId);
      // Content-type parser always yields a Buffer for application/octet-stream.
      if (!Buffer.isBuffer(req.body)) {
        throw new BadRequest("expected application/octet-stream body");
      }
      const bytes = req.body;
      if (sha256Hex(bytes) !== hash) {
        throw new BadRequest(`blob ${hash} does not match its sha256`);
      }
      await deps.storage.put(hash, bytes);
      return { ok: true, hash, bytes: bytes.length };
    },
  );

  /** Raw ink blob download. Used when pull omits large Base64 blobs. */
  app.get<{ Params: { hash: string } }>("/sync/blob/:hash", async (req, reply) => {
    const hash = (req.params.hash || "").toLowerCase();
    if (!SHA256_HEX.test(hash)) {
      throw new BadRequest("hash must be a sha256 hex digest");
    }
    if (!(await deps.storage.has(hash))) {
      throw new NotFound(`blob ${hash}`);
    }
    const bytes = await deps.storage.get(hash);
    return reply
      .header("Content-Type", "application/octet-stream")
      .header("Content-Length", String(bytes.length))
      .header("X-Zetteldraw-Ink-Hash", hash)
      .send(bytes);
  });

  app.post("/sync/push", async (req) => {
    const body = parsePushBody(req.body);
    if (body.schema_version !== deps.schemaVersion) {
      throw new BadRequest(`schema_version ${body.schema_version} != ${deps.schemaVersion}`);
    }
    const scope = scopeOf(req.principal);
    await assertCanPush(deps.pool, scope, body.device_id);
    const results = await push(deps.pool, deps.storage, body, scope);
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

  /** Metadata-only: live log id/hash/bytes for adopting longer cloud logs after cursor skip. */
  app.get("/sync/log-heads", async (req) => {
    return { logs: await logHeads(deps.pool, scopeOf(req.principal)) };
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
    registerWebRoutes(app, deps.pool, deps.thumbs, (req) => scopeOf(req.principal), deps.storage);
  }

  return app;
}

/** Helper for tests that build a bare FastifyRequest-like principal. */
export function principalScope(req: FastifyRequest): SyncScope {
  return scopeOf(req.principal);
}
