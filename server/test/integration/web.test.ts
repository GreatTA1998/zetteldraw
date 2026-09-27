import { randomUUID } from "node:crypto";
import { beforeAll, describe, expect, it } from "vitest";
import { encodeInk, type InkStroke } from "../../src/ink.js";
import { S3InkStorage } from "../../src/storage.js";
import { sha256Hex } from "../../src/sync.js";
import { imageKey } from "../../src/thumbs.js";

// Runs against a live stack: `npm run test:integration` brings it up with docker compose.
const URL_BASE = process.env.SYNC_URL ?? "http://127.0.0.1:8787";
const TOKEN = process.env.SYNC_TOKEN ?? "dev-device-token";

const storage = S3InkStorage.fromConfig({
  endpoint: process.env.S3_ENDPOINT ?? "http://127.0.0.1:59000",
  region: "us-east-1",
  bucket: process.env.S3_BUCKET ?? "zetteldraw-ink",
  accessKeyId: process.env.S3_ACCESS_KEY ?? "zetteldraw",
  secretAccessKey: process.env.S3_SECRET_KEY ?? "zetteldraw-secret",
});

let schema = 0;

async function call(method: string, path: string, body?: unknown, token = TOKEN) {
  const res = await fetch(URL_BASE + path, {
    method,
    headers: {
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(path.startsWith("/sync/") ? { "x-zetteldraw-schema": String(schema) } : {}),
      ...(body ? { "content-type": "application/json" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const type = res.headers.get("content-type") ?? "";
  return {
    status: res.status,
    type,
    json: type.includes("json") ? ((await res.json()) as any) : null,
    bytes: type.includes("json") ? null : Buffer.from(await res.arrayBuffer()),
    headers: res.headers,
  };
}

function ink(seed: number) {
  const stroke: InkStroke = Array.from({ length: 30 }, (_, i) => ({
    x: 100 + i * 20,
    y: 200 + seed * 40 + Math.sin(i) * 30,
    pressure: 0.5,
    size: 1,
    tiltX: 0,
    tiltY: 0,
    t: 1_700_000_000_000 + i * 8,
  }));
  // Alternate versions: both are still on devices.
  const bytes = Math.round(seed) % 2 ? encodeInk([stroke], { version: 1 }) : encodeInk([stroke], { ids: [randomUUID()] });
  return { bytes, hash: sha256Hex(bytes) };
}

const now = Date.now() - 60_000;
// Unique per run, so notebooks left by earlier runs against the same database never interleave.
const notebookKey = `zz${Date.now().toString(36)}`;
const notebookA = randomUUID();
const notebookB = randomUUID();
const pages = [randomUUID(), randomUUID(), randomUUID(), randomUUID()];
const inks = pages.map((_, i) => ink(i + Math.random()));

async function pullAll(since: number) {
  const boards: any[] = [];
  const notebooks: any[] = [];
  let cursor = since;
  for (;;) {
    const res = await call("GET", `/sync/pull?since=${cursor}&limit=500`);
    boards.push(...res.json.boards);
    notebooks.push(...res.json.notebooks);
    cursor = res.json.cursor;
    if (!res.json.has_more) break;
  }
  return { boards, notebooks, cursor };
}

async function pageIds(notebook: string): Promise<string[]> {
  const res = await call("GET", `/web/notebooks/${notebook}/pages`);
  expect(res.status).toBe(200);
  return res.json.pages.map((p: { id: string }) => p.id);
}

beforeAll(async () => {
  const health = await fetch(URL_BASE + "/healthz");
  schema = ((await health.json()) as { schema_version: number }).schema_version;
  const notebook = (id: string, title: string, position: string) => ({
    id,
    title,
    position,
    created_at: now,
    updated_at: now,
    deleted_at: null,
    base_rev: 0,
  });
  const board = (i: number, notebookId: string | null, position: string) => ({
    id: pages[i],
    notebook_id: notebookId,
    position,
    ink_hash: inks[i].hash,
    ink_bytes: inks[i].bytes.length,
    thumb_hash: null,
    conflict_of: null,
    created_at: now,
    updated_at: now,
    deleted_at: null,
    base_rev: 0,
  });
  const res = await call("POST", "/sync/push", {
    schema_version: schema,
    device_id: "web-test",
    notebooks: [notebook(notebookA, "Web test A", notebookKey), notebook(notebookB, "Web test B", `${notebookKey}V`)],
    boards: [board(0, notebookA, "a0"), board(1, notebookA, "a1"), board(2, notebookA, "a2"), board(3, notebookB, "a0")],
    blobs: Object.fromEntries(inks.map((k) => [k.hash, k.bytes.toString("base64")])),
  });
  expect(res.status).toBe(200);
});

describe("web auth", () => {
  it("requires the device token but not the schema header", async () => {
    expect((await call("GET", "/web/notebooks", undefined, "")).status).toBe(401);
    expect((await call("GET", "/web/notebooks", undefined, "wrong")).status).toBe(401);
    expect((await call("GET", "/web/session")).json).toMatchObject({ ok: true });
  });
});

describe("web reads", () => {
  it("lists notebooks in order with page counts, scratchpad first", async () => {
    const res = await call("GET", "/web/notebooks");
    expect(res.status).toBe(200);
    const list = res.json.notebooks;
    expect(list[0]).toMatchObject({ id: "scratchpad", kind: "scratchpad" });
    const a = list.findIndex((n: any) => n.id === notebookA);
    const b = list.findIndex((n: any) => n.id === notebookB);
    expect(a).toBeGreaterThan(0);
    expect(b).toBe(a + 1);
    expect(list[a]).toMatchObject({ title: "Web test A", page_count: 3 });
  });

  it("lists pages in position order with image urls", async () => {
    const res = await call("GET", `/web/notebooks/${notebookA}/pages`);
    expect(res.json.pages.map((p: any) => p.id)).toEqual(pages.slice(0, 3));
    expect(res.json.pages[0]).toMatchObject({
      thumb_url: `/web/thumbs/${inks[0].hash}.png`,
      render_url: `/web/renders/${inks[0].hash}.png`,
    });
    expect((await call("GET", `/web/notebooks/${randomUUID()}/pages`)).status).toBe(404);
    expect((await call("GET", `/web/notebooks/not-a-uuid/pages`)).status).toBe(404);
    expect((await call("GET", `/web/notebooks/scratchpad/pages`)).status).toBe(200);
  });

  it("renders thumbnails on push and serves PNGs", async () => {
    let stored = false;
    for (let i = 0; i < 50 && !stored; i++) {
      stored = await storage.hasObject(imageKey("thumb", inks[1].hash));
      if (!stored) await new Promise((r) => setTimeout(r, 100));
    }
    expect(stored).toBe(true);

    const thumb = await call("GET", `/web/thumbs/${inks[1].hash}.png`);
    expect(thumb.status).toBe(200);
    expect(thumb.type).toBe("image/png");
    expect(thumb.headers.get("cache-control")).toContain("immutable");
    expect(thumb.bytes!.readUInt32BE(16)).toBe(480);

    const full = await call("GET", `/web/renders/${inks[1].hash}.png`);
    expect(full.status).toBe(200);
    expect(full.bytes!.readUInt32BE(16)).toBe(1264);

    expect((await call("GET", `/web/thumbs/${"0".repeat(64)}.png`)).status).toBe(404);

    const junk = Buffer.from(`ZDI\u0009not ink ${randomUUID()}`, "latin1");
    await storage.put(sha256Hex(junk), junk);
    const unreadable = await call("GET", `/web/thumbs/${sha256Hex(junk)}.png`);
    expect(unreadable.status).toBe(422);
    expect(unreadable.json.error).toBe("unreadable_ink");
    expect((await call("GET", `/web/thumbs/../../etc.png`)).status).toBe(404);
  });
});

describe("web edits", () => {
  it("reorders within a notebook and the device pulls the new position", async () => {
    const before = await pullAll(0);
    const res = await call("POST", `/web/pages/${pages[0]}/move`, { notebook_id: notebookA, after_id: pages[2] });
    expect(res.status).toBe(200);
    expect(res.json.status).toBe("moved");
    expect(await pageIds(notebookA)).toEqual([pages[1], pages[2], pages[0]]);

    const pulled = await pullAll(before.cursor);
    const row = pulled.boards.find((b) => b.id === pages[0]);
    expect(row).toMatchObject({ notebook_id: notebookA, position: res.json.page.position, rev: res.json.page.rev });
    expect(row.position > "a2").toBe(true);
    expect(row.updated_at).toBeGreaterThan(now);
    expect(pulled.boards.filter((b) => b.id !== pages[0] && pages.includes(b.id))).toEqual([]);
  });

  it("moves to the start, and reports an unchanged drop", async () => {
    const res = await call("POST", `/web/pages/${pages[0]}/move`, { notebook_id: notebookA, after_id: null });
    expect(res.json.status).toBe("moved");
    expect(await pageIds(notebookA)).toEqual([pages[0], pages[1], pages[2]]);
    const again = await call("POST", `/web/pages/${pages[0]}/move`, { notebook_id: notebookA, after_id: null });
    expect(again.json).toMatchObject({ status: "unchanged", page: { rev: res.json.page.rev } });
  });

  it("moves a page to another notebook and to the scratchpad", async () => {
    const res = await call("POST", `/web/pages/${pages[1]}/move`, { notebook_id: notebookB, after_id: pages[3] });
    expect(res.status).toBe(200);
    expect(await pageIds(notebookA)).toEqual([pages[0], pages[2]]);
    expect(await pageIds(notebookB)).toEqual([pages[3], pages[1]]);

    const toEnd = await call("POST", `/web/pages/${pages[2]}/move`, { notebook_id: notebookB });
    expect(toEnd.json.page.notebook_id).toBe(notebookB);
    expect(await pageIds(notebookB)).toEqual([pages[3], pages[1], pages[2]]);

    const scratch = await call("POST", `/web/pages/${pages[2]}/move`, { notebook_id: "scratchpad" });
    expect(scratch.json.page.notebook_id).toBeNull();
    expect((await pageIds("scratchpad")).at(-1)).toBe(pages[2]);
  });

  it("rejects bad moves", async () => {
    expect((await call("POST", `/web/pages/${randomUUID()}/move`, { notebook_id: notebookA })).status).toBe(404);
    expect((await call("POST", `/web/pages/${pages[0]}/move`, { notebook_id: randomUUID() })).status).toBe(404);
    expect((await call("POST", `/web/pages/${pages[0]}/move`, { notebook_id: notebookA, after_id: pages[3] })).status).toBe(400);
    expect((await call("POST", `/web/pages/${pages[0]}/move`, { notebook_id: 7 })).status).toBe(400);
  });

  it("renames a notebook as a synced change", async () => {
    const before = await pullAll(0);
    const res = await call("PATCH", `/web/notebooks/${notebookA}`, { title: "  Renamed on the web  " });
    expect(res.status).toBe(200);
    expect(res.json.notebook.title).toBe("Renamed on the web");
    const pulled = await pullAll(before.cursor);
    expect(pulled.notebooks.find((n) => n.id === notebookA)).toMatchObject({ title: "Renamed on the web" });

    expect((await call("PATCH", `/web/notebooks/${notebookA}`, { title: "   " })).status).toBe(400);
    expect((await call("PATCH", `/web/notebooks/scratchpad`, { title: "x" })).status).toBe(400);
    expect((await call("PATCH", `/web/notebooks/${randomUUID()}`, { title: "x" })).status).toBe(404);
  });

  it("a stale device edit loses to the web edit under LWW", async () => {
    const current = (await call("GET", `/web/notebooks/${notebookB}/pages`)).json.pages.find((p: any) => p.id === pages[3]);
    const moved = await call("POST", `/web/pages/${pages[3]}/move`, { notebook_id: notebookB });
    expect(moved.json.status).toBe("moved");
    const stale = await call("POST", "/sync/push", {
      schema_version: schema,
      device_id: "web-test",
      notebooks: [],
      boards: [
        {
          id: pages[3],
          notebook_id: notebookB,
          position: "a0",
          ink_hash: inks[3].hash,
          ink_bytes: inks[3].bytes.length,
          thumb_hash: null,
          conflict_of: null,
          created_at: now,
          updated_at: now + 1,
          deleted_at: null,
          base_rev: current.rev,
        },
      ],
      blobs: {},
    });
    expect(stale.json.results[0].status).toBe("conflict_lost");
    expect((await pageIds(notebookB)).at(-1)).toBe(pages[3]);
  });
});
