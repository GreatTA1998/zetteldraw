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

function logRow(
  id: string,
  notebookId: string | null,
  ink: ReturnType<typeof blob> | null,
  updatedAt: number,
  baseRev: number,
  sliceHeight = 800,
) {
  return {
    id,
    notebook_id: notebookId,
    ink_hash: ink?.hash ?? null,
    ink_bytes: ink?.bytes.length ?? 0,
    slice_height: sliceHeight,
    conflict_of: null,
    created_at: 1_700_000_000_000,
    updated_at: updatedAt,
    deleted_at: null,
    base_rev: baseRev,
  };
}

async function pullAll(since: number) {
  const logs: any[] = [];
  const notebooks: any[] = [];
  const boards: any[] = [];
  const blobs: Record<string, string> = {};
  let cursor = since;
  for (;;) {
    const res = await call("GET", `/sync/pull?since=${cursor}&limit=2`);
    expect(res.status).toBe(200);
    logs.push(...res.json.logs);
    notebooks.push(...res.json.notebooks);
    boards.push(...(res.json.boards ?? []));
    Object.assign(blobs, res.json.blobs);
    expect(res.json.cursor).toBeGreaterThanOrEqual(cursor);
    cursor = res.json.cursor;
    if (!res.json.has_more) break;
  }
  return { logs, notebooks, boards, blobs, cursor };
}

beforeAll(async () => {
  const health = await fetch(URL_BASE + "/healthz");
  schema = ((await health.json()) as { schema_version: number }).schema_version;
});

describe("sync round trip", () => {
  const notebookId = randomUUID();
  const logId = randomUUID();
  const v1 = blob(`ink v1 ${logId}`);
  let startCursor = 0;
  let rev1 = 0;

  it("rejects a wrong token and a wrong schema version", async () => {
    expect((await call("GET", "/sync/pull?since=0", undefined, { authorization: "Bearer nope" })).status).toBe(401);
    const wrong = await call("GET", "/sync/pull?since=0", undefined, { "x-zetteldraw-schema": "999" });
    expect(wrong.status).toBe(409);
    expect(wrong.json.server_schema).toBe(schema);
  });

  it("rejects BOARD push (Ready LOG is cloud ink ingress)", async () => {
    const res = await call("POST", "/sync/push", {
      schema_version: schema,
      device_id: "device-a",
      notebooks: [],
      boards: [
        {
          id: randomUUID(),
          notebook_id: null,
          position: "a0",
          ink_hash: null,
          ink_bytes: 0,
          thumb_hash: null,
          conflict_of: null,
          created_at: 1,
          updated_at: 1,
          deleted_at: null,
          base_rev: 0,
        },
      ],
      logs: [],
      links: [],
      blobs: {},
    });
    expect(res.status).toBe(400);
  });

  it("pushes a notebook and a Ready log with its ink blob", async () => {
    startCursor = (await pullAll(0)).cursor;
    const res = await call("POST", "/sync/push", {
      schema_version: schema,
      device_id: "device-a",
      notebooks: [
        { id: notebookId, title: "comedy", position: "a0", created_at: 1, updated_at: 1, deleted_at: null, base_rev: 0 },
      ],
      boards: [],
      logs: [logRow(logId, notebookId, v1, 1_000, 0)],
      links: [],
      blobs: { [v1.hash]: v1.b64 },
    });
    expect(res.status).toBe(200);
    expect(res.json.results).toHaveLength(2);
    for (const r of res.json.results) expect(r.status).toBe("applied");
    rev1 = res.json.results.find((r: any) => r.entity === "log").rev;
    expect(rev1).toBeGreaterThan(startCursor);
    expect(await storage.has(v1.hash)).toBe(true);
    expect((await storage.get(v1.hash)).equals(v1.bytes)).toBe(true);
  });

  it("pulls notebook + log (boards always empty on pull)", async () => {
    const page = await pullAll(startCursor);
    expect(page.boards).toEqual([]);
    const log = page.logs.find((x) => x.id === logId);
    expect(log).toMatchObject({ notebook_id: notebookId, ink_hash: v1.hash, rev: rev1, conflict_of: null });
    expect(page.notebooks.find((x) => x.id === notebookId)?.title).toBe("comedy");
    expect(Buffer.from(page.blobs[v1.hash], "base64").equals(v1.bytes)).toBe(true);
  });

  it("a stale, newer log edit from another device wins; the old ink survives as a hidden copy", async () => {
    const v2 = blob(`ink v2 ${logId} longer-enough`);
    const before = (await pullAll(0)).cursor;
    const res = await call("POST", "/sync/push", {
      schema_version: schema,
      device_id: "device-b",
      notebooks: [],
      boards: [],
      logs: [logRow(logId, notebookId, v2, 2_000, 0)],
      links: [],
      blobs: { [v2.hash]: v2.b64 },
    });
    expect(res.json.results[0].status).toBe("conflict_won");
    const page = await pullAll(before);
    expect(page.logs.find((x) => x.id === logId)?.ink_hash).toBe(v2.hash);
    const copy = page.logs.find((x) => x.conflict_of === logId);
    expect(copy?.ink_hash).toBe(v1.hash);
    expect(page.blobs[v1.hash]).toBeDefined();
  });

  it("a stale, older log edit loses but is kept as a copy, and the winner is re-sent", async () => {
    const v3 = blob(`ink v3 ${logId} also-long-enough`);
    const before = (await pullAll(0)).cursor;
    const res = await call("POST", "/sync/push", {
      schema_version: schema,
      device_id: "device-a",
      notebooks: [],
      boards: [],
      logs: [logRow(logId, notebookId, v3, 1_500, rev1)],
      links: [],
      blobs: { [v3.hash]: v3.b64 },
    });
    expect(res.json.results[0].status).toBe("conflict_lost");
    const page = await pullAll(before);
    expect(page.logs.find((x) => x.id === logId)?.updated_at).toBe(2_000);
    expect(page.logs.some((x) => x.conflict_of === logId && x.ink_hash === v3.hash)).toBe(true);
  });

  it("carries notebook create, rename and delete (tombstone)", async () => {
    const nbId = randomUUID();
    const notebook = (title: string, updated: number, baseRev: number, deletedAt: number | null = null) => ({
      id: nbId,
      title,
      position: "b0",
      created_at: 10,
      updated_at: updated,
      deleted_at: deletedAt,
      base_rev: baseRev,
    });
    const before = (await pullAll(0)).cursor;

    const created = await call("POST", "/sync/push", {
      schema_version: schema,
      device_id: "device-a",
      notebooks: [notebook("ideas", 10, 0)],
      boards: [],
      logs: [],
      links: [],
      blobs: {},
    });
    expect(created.json.results.map((r: any) => r.status)).toEqual(["applied"]);
    const nbRev = created.json.results[0].rev;

    const renamed = await call("POST", "/sync/push", {
      schema_version: schema,
      device_id: "device-b",
      notebooks: [notebook("ideas, sorted", 20, nbRev)],
      boards: [],
      logs: [],
      links: [],
      blobs: {},
    });
    expect(renamed.json.results[0].status).toBe("applied");
    const renamedRev = renamed.json.results[0].rev;
    expect(renamedRev).toBeGreaterThan(nbRev);
    let page = await pullAll(before);
    expect(page.notebooks.filter((n) => n.id === nbId)).toEqual([
      expect.objectContaining({ title: "ideas, sorted", rev: renamedRev, deleted_at: null }),
    ]);

    const deleted = await call("POST", "/sync/push", {
      schema_version: schema,
      device_id: "device-a",
      notebooks: [notebook("ideas, sorted", 30, renamedRev, 30)],
      boards: [],
      logs: [],
      links: [],
      blobs: {},
    });
    expect(deleted.json.results.map((r: any) => r.status)).toEqual(["applied"]);
    page = await pullAll(renamedRev);
    expect(page.notebooks.find((n) => n.id === nbId)).toMatchObject({ deleted_at: 30 });

    const stale = await call("POST", "/sync/push", {
      schema_version: schema,
      device_id: "device-c",
      notebooks: [notebook("resurrected?", 25, nbRev)],
      boards: [],
      logs: [],
      links: [],
      blobs: {},
    });
    expect(stale.json.results[0].status).toBe("conflict_lost");
    page = await pullAll(before);
    const latest = page.notebooks.filter((n) => n.id === nbId).pop();
    expect(latest).toMatchObject({ deleted_at: 30, title: "ideas, sorted" });
  });

  it("reports missing blobs on logs and rejects blobs that do not match their hash", async () => {
    const ghost = blob(`never uploaded ${randomUUID()}`);
    const missing = await call("POST", "/sync/push", {
      schema_version: schema,
      device_id: "device-a",
      boards: [],
      logs: [logRow(randomUUID(), null, ghost, 1, 0)],
      links: [],
      blobs: {},
    });
    expect(missing.json.results[0].status).toBe("missing_blob");
    const bad = await call("POST", "/sync/push", {
      schema_version: schema,
      device_id: "device-a",
      boards: [],
      logs: [],
      links: [],
      blobs: { [ghost.hash]: Buffer.from("tampered").toString("base64") },
    });
    expect(bad.status).toBe(400);
  });
});
