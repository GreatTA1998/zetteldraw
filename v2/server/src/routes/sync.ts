import { Hono } from "hono";

/**
 * Sync route stubs for the greenfield rewrite.
 *
 * Contract: docs/greenfield/sync-model.md
 * - One live Boox writer; cloud is backup
 * - Day-to-day: push ink; live writer does not adopt remote bodies
 * - Ceremony: Prepare transfer → Hand off → claim
 *
 * These handlers return 501 until the real implementations land.
 * Do not port legacy dual-.zdl / RoomBoardRepository paths.
 */
export const syncRoutes = new Hono();

/** Live writer uploads ink outbox / blobs. Reject non-writers with 403 not_live_writer. */
syncRoutes.post("/push", (c) =>
  c.json(
    {
      error: "not_implemented",
      stub: "POST /sync/push — live-writer ink upload (outbox + optional blob hashes)",
    },
    501,
  ),
);

/**
 * Meta / thumbs / optional catch-up for non-writer.
 * Live writer: must not replace local ink bodies (push-only while leased).
 */
syncRoutes.post("/pull", (c) =>
  c.json(
    {
      error: "not_implemented",
      stub: "POST /sync/pull — meta + catch-up; never short-overwrite live local",
    },
    501,
  ),
);

/** Per-notebook length heads for catch-up / refuse-shorter decisions (client-side apply). */
syncRoutes.get("/log-heads", (c) =>
  c.json(
    {
      error: "not_implemented",
      stub: "GET /sync/log-heads — per-notebook length authority (not a global cursor)",
    },
    501,
  ),
);

/** Large ink blobs (> ~256 KiB) as raw body; avoid Base64 OOM on device. */
syncRoutes.put("/blob/:hash", (c) =>
  c.json(
    {
      error: "not_implemented",
      stub: `PUT /sync/blob/${c.req.param("hash")} — raw ink blob upload`,
    },
    501,
  ),
);

syncRoutes.get("/blob/:hash", (c) =>
  c.json(
    {
      error: "not_implemented",
      stub: `GET /sync/blob/${c.req.param("hash")} — raw ink blob download`,
    },
    501,
  ),
);

/** Live Boox Settings → Prepare transfer (final sync + server ack). Stays on device. */
syncRoutes.post("/lease/prepare", (c) =>
  c.json(
    {
      error: "not_implemented",
      stub: "POST /sync/lease/prepare — unlock Hand off when backup ready",
    },
    501,
  ),
);

/** Web Settings → Hand off (requires Prepare or lost-device + backup ready). */
syncRoutes.post("/lease/hand-off", (c) =>
  c.json(
    {
      error: "not_implemented",
      stub: "POST /sync/lease/hand-off — block old device; open claim window",
    },
    501,
  ),
);

/** New Boox first successful claim becomes live writer. */
syncRoutes.post("/lease/claim", (c) =>
  c.json(
    {
      error: "not_implemented",
      stub: "POST /sync/lease/claim — new device becomes live writer",
    },
    501,
  ),
);

/** Web Cancel Hand off — restore previous live writer. */
syncRoutes.post("/lease/cancel", (c) =>
  c.json(
    {
      error: "not_implemented",
      stub: "POST /sync/lease/cancel — restore previous live writer",
    },
    501,
  ),
);

/** Backup-status gate for Hand off UI (logs + boards in object storage). */
syncRoutes.get("/backup-status", (c) =>
  c.json(
    {
      error: "not_implemented",
      stub: "GET /sync/backup-status — ready flag for Hand off gate",
    },
    501,
  ),
);
