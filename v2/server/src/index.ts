import { serve } from "@hono/node-server";
import { Hono } from "hono";
import { syncRoutes } from "./routes/sync.js";

/**
 * Greenfield sync stub — empty routes documenting the locked ceremony
 * from docs/greenfield/sync-model.md. No legacy server port.
 */
const app = new Hono();

app.get("/health", (c) => c.json({ ok: true, service: "zetteldraw-v2-server" }));
app.route("/sync", syncRoutes);

const port = Number(process.env.PORT ?? 3847);
console.log(`zetteldraw-v2 sync stub listening on http://127.0.0.1:${port}`);
serve({ fetch: app.fetch, port });
