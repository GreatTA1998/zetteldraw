import { createHash, randomUUID } from "node:crypto";
import { beforeAll, describe, expect, it } from "vitest";

// Runs against a live stack: `npm run test:integration` brings it up with docker compose.
const URL_BASE = process.env.SYNC_URL ?? "http://127.0.0.1:8787";
const TOKEN = process.env.SYNC_TOKEN ?? "dev-device-token";

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

/** Minimal Ready ZDL: header slices only (matches server readSliceIndex). */
function zdlHeader(pageIds: string[], height = 1407): { bytes: Buffer; hash: string; b64: string } {
  const parts: Buffer[] = [];
  parts.push(Buffer.from([0x5a, 0x44, 0x4c, 1]));
  const head = Buffer.alloc(8);
  head.writeInt32BE(height, 0);
  head.writeInt32BE(pageIds.length, 4);
  parts.push(head);
  for (const pageId of pageIds) {
    const id = Buffer.from(pageId, "utf8");
    const row = Buffer.alloc(4 + 2 + id.length);
    row.writeInt32BE(height, 0);
    row.writeUInt16BE(id.length, 4);
    id.copy(row, 6);
    parts.push(row);
  }
  const bytes = Buffer.concat(parts);
  return {
    bytes,
    hash: createHash("sha256").update(bytes).digest("hex"),
    b64: bytes.toString("base64"),
  };
}

function logRow(
  id: string,
  notebookId: string | null,
  zdl: { hash: string; bytes: Buffer },
  updatedAt: number,
) {
  return {
    id,
    notebook_id: notebookId,
    ink_hash: zdl.hash,
    ink_bytes: zdl.bytes.length,
    slice_height: 1407,
    conflict_of: null,
    created_at: updatedAt,
    updated_at: updatedAt,
    deleted_at: null,
    base_rev: 0,
  };
}

const now = Date.now() - 60_000;
// Unique per run, so notebooks left by earlier runs against the same database never interleave.
const notebookKey = `zz${Date.now().toString(36)}`;
const notebookA = randomUUID();
const notebookB = randomUUID();
const pages = [randomUUID(), randomUUID(), randomUUID(), randomUUID()];
const logA = randomUUID();
const logB = randomUUID();
const zdlA = zdlHeader(pages.slice(0, 3));
const zdlB = zdlHeader(pages.slice(3, 4));

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
  // Ready LOG is companion page SoT — push notebooks + ZDL logs (no boards list dual).
  const res = await call("POST", "/sync/push", {
    schema_version: schema,
    device_id: "web-test",
    notebooks: [notebook(notebookA, "Web test A", notebookKey), notebook(notebookB, "Web test B", `${notebookKey}V`)],
    boards: [],
    logs: [logRow(logA, notebookA, zdlA, now), logRow(logB, notebookB, zdlB, now + 1)],
    links: [],
    blobs: { [zdlA.hash]: zdlA.b64, [zdlB.hash]: zdlB.b64 },
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

  it("lists pages in Ready order and exposes ink_url", async () => {
    const res = await call("GET", `/web/notebooks/${notebookA}/pages`);
    expect(res.json.pages.map((p: any) => p.id)).toEqual(pages.slice(0, 3));
    expect(res.json.ink_url).toBe(`/web/notebooks/${notebookA}/ink`);
    expect(res.json.pages[0].thumb_url).toBeUndefined();
    expect((await call("GET", `/web/notebooks/${randomUUID()}/pages`)).status).toBe(404);
    expect((await call("GET", `/web/notebooks/not-a-uuid/pages`)).status).toBe(404);
    expect((await call("GET", `/web/notebooks/scratchpad/pages`)).status).toBe(200);
  });

  it("serves Ready .zdl bytes for client stroke replay", async () => {
    const ink = await call("GET", `/web/notebooks/${notebookA}/ink`);
    expect(ink.status).toBe(200);
    expect(ink.type).toContain("octet-stream");
    expect(ink.bytes!.subarray(0, 3).toString("latin1")).toBe("ZDL");
    expect(ink.bytes!.equals(zdlA.bytes)).toBe(true);
    expect((await call("GET", `/web/notebooks/${randomUUID()}/ink`)).status).toBe(404);
    expect((await call("GET", `/web/thumbs/${"0".repeat(64)}.png`)).status).toBe(404);
  });
});

describe("web is read-only", () => {
  it("refuses rename and move", async () => {
    expect((await call("PATCH", `/web/notebooks/${notebookA}`, { title: "nope" })).status).toBe(405);
    expect((await call("POST", `/web/pages/${pages[0]}/move`, { notebook_id: notebookB })).status).toBe(405);
  });
});
