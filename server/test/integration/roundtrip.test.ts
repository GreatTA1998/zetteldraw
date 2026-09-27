import { createHash, randomUUID } from "node:crypto";
import { beforeAll, describe, expect, it } from "vitest";
import { S3InkStorage } from "../../src/storage.js";

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

async function call(method: string, path: string, body?: unknown, headers: Record<string, string> = {}) {
  const res = await fetch(URL_BASE + path, {
    method,
    headers: {
      authorization: `Bearer ${TOKEN}`,
      "x-zetteldraw-schema": String(schema),
      ...(body ? { "content-type": "application/json" } : {}),
      ...headers,
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, json: (await res.json()) as any };
}

function blob(text: string) {
  const bytes = Buffer.from(text);
  return { hash: createHash("sha256").update(bytes).digest("hex"), b64: bytes.toString("base64"), bytes };
}

function boardRow(id: string, notebookId: string | null, ink: ReturnType<typeof blob> | null, updatedAt: number, baseRev: number) {
  return {
    id,
    notebook_id: notebookId,
    position: "a0",
    ink_hash: ink?.hash ?? null,
    ink_bytes: ink?.bytes.length ?? 0,
    thumb_hash: null,
    conflict_of: null,
    created_at: 1_700_000_000_000,
    updated_at: updatedAt,
    deleted_at: null,
    base_rev: baseRev,
  };
}

async function pullAll(since: number) {
  const boards: any[] = [];
  const notebooks: any[] = [];
  const blobs: Record<string, string> = {};
  let cursor = since;
  for (;;) {
    const res = await call("GET", `/sync/pull?since=${cursor}&limit=2`);
    expect(res.status).toBe(200);
    boards.push(...res.json.boards);
    notebooks.push(...res.json.notebooks);
    Object.assign(blobs, res.json.blobs);
    expect(res.json.cursor).toBeGreaterThanOrEqual(cursor);
    cursor = res.json.cursor;
    if (!res.json.has_more) break;
  }
  return { boards, notebooks, blobs, cursor };
}

beforeAll(async () => {
  const health = await fetch(URL_BASE + "/healthz");
  schema = ((await health.json()) as { schema_version: number }).schema_version;
});

describe("sync round trip", () => {
  const notebookId = randomUUID();
  const boardId = randomUUID();
  const v1 = blob(`ink v1 ${boardId}`);
  let startCursor = 0;
  let rev1 = 0;

  it("rejects a wrong token and a wrong schema version", async () => {
    expect((await call("GET", "/sync/pull?since=0", undefined, { authorization: "Bearer nope" })).status).toBe(401);
    const wrong = await call("GET", "/sync/pull?since=0", undefined, { "x-zetteldraw-schema": "999" });
    expect(wrong.status).toBe(409);
    expect(wrong.json.server_schema).toBe(schema);
  });

  it("pushes a notebook and a board with its ink blob", async () => {
    startCursor = (await pullAll(0)).cursor;
    const res = await call("POST", "/sync/push", {
      schema_version: schema,
      device_id: "device-a",
      notebooks: [
        { id: notebookId, title: "comedy", position: "a0", created_at: 1, updated_at: 1, deleted_at: null, base_rev: 0 },
      ],
      boards: [boardRow(boardId, notebookId, v1, 1_000, 0)],
      blobs: { [v1.hash]: v1.b64 },
    });
    expect(res.status).toBe(200);
    expect(res.json.results).toHaveLength(2);
    for (const r of res.json.results) expect(r.status).toBe("applied");
    rev1 = res.json.results.find((r: any) => r.entity === "board").rev;
    expect(rev1).toBeGreaterThan(startCursor);
    expect(await storage.has(v1.hash)).toBe(true);
    expect((await storage.get(v1.hash)).equals(v1.bytes)).toBe(true);
  });

  it("pulls both rows back with the ink inline", async () => {
    const page = await pullAll(startCursor);
    const b = page.boards.find((x) => x.id === boardId);
    expect(b).toMatchObject({ notebook_id: notebookId, ink_hash: v1.hash, rev: rev1, conflict_of: null });
    expect(page.notebooks.find((x) => x.id === notebookId)?.title).toBe("comedy");
    expect(Buffer.from(page.blobs[v1.hash], "base64").equals(v1.bytes)).toBe(true);
  });

  it("a stale, newer edit from another device wins; the old ink survives as a hidden copy", async () => {
    const v2 = blob(`ink v2 ${boardId}`);
    const before = (await pullAll(0)).cursor;
    const res = await call("POST", "/sync/push", {
      schema_version: schema,
      device_id: "device-b",
      notebooks: [],
      boards: [boardRow(boardId, notebookId, v2, 2_000, 0)],
      blobs: { [v2.hash]: v2.b64 },
    });
    expect(res.json.results[0].status).toBe("conflict_won");
    const page = await pullAll(before);
    expect(page.boards.find((x) => x.id === boardId)?.ink_hash).toBe(v2.hash);
    const copy = page.boards.find((x) => x.conflict_of === boardId);
    expect(copy?.ink_hash).toBe(v1.hash);
    expect(page.blobs[v1.hash]).toBeDefined();
  });

  it("a stale, older edit loses but is kept as a copy, and the winner is re-sent", async () => {
    const v3 = blob(`ink v3 ${boardId}`);
    const before = (await pullAll(0)).cursor;
    const res = await call("POST", "/sync/push", {
      schema_version: schema,
      device_id: "device-a",
      notebooks: [],
      boards: [boardRow(boardId, notebookId, v3, 1_500, rev1)],
      blobs: { [v3.hash]: v3.b64 },
    });
    expect(res.json.results[0].status).toBe("conflict_lost");
    const page = await pullAll(before);
    expect(page.boards.find((x) => x.id === boardId)?.updated_at).toBe(2_000);
    expect(page.boards.some((x) => x.conflict_of === boardId && x.ink_hash === v3.hash)).toBe(true);
  });

  it("reports missing blobs and rejects blobs that do not match their hash", async () => {
    const ghost = blob(`never uploaded ${randomUUID()}`);
    const missing = await call("POST", "/sync/push", {
      schema_version: schema,
      device_id: "device-a",
      boards: [boardRow(randomUUID(), null, ghost, 1, 0)],
    });
    expect(missing.json.results[0].status).toBe("missing_blob");
    const bad = await call("POST", "/sync/push", {
      schema_version: schema,
      device_id: "device-a",
      boards: [],
      blobs: { [ghost.hash]: Buffer.from("tampered").toString("base64") },
    });
    expect(bad.status).toBe(400);
  });
});
