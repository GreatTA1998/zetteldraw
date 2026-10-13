import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import { buildApp } from "../../src/app.js";
import { AuthService } from "../../src/auth.js";
import { INLINE_BLOB_MAX_BYTES, sha256Hex } from "../../src/sync.js";
import type { InkStorage } from "../../src/storage.js";

class MemoryStorage implements InkStorage {
  readonly map = new Map<string, Buffer>();
  async has(hash: string) {
    return this.map.has(hash);
  }
  async put(hash: string, bytes: Buffer) {
    this.map.set(hash, bytes);
  }
  async get(hash: string) {
    const b = this.map.get(hash);
    if (!b) throw new Error("missing " + hash);
    return b;
  }
}

function poolStub() {
  return {
    connect: async () => ({
      query: async () => ({ rows: [] }),
      release: () => {},
    }),
    query: async (sql: string) => {
      if (sql.includes("library_claim")) {
        return { rows: [] };
      }
      return { rows: [] };
    },
  } as any;
}

function testApp(storage: MemoryStorage) {
  return buildApp({
    pool: poolStub(),
    storage,
    schemaVersion: 4,
    auth: new AuthService({
      deviceTokens: ["tok"],
      googleClientIds: [],
      sessionSecret: undefined,
      allowTestGoogleTokens: false,
      rejectDeviceTokensAfterClaim: false,
    }),
    sessionSecret: undefined,
    logger: false,
  });
}

describe("raw /sync/blob", () => {
  it("PUT stores by sha256 and GET returns octets", async () => {
    const storage = new MemoryStorage();
    const app = testApp(storage);
    await app.ready();
    const bytes = Buffer.alloc(INLINE_BLOB_MAX_BYTES + 1024, 7);
    const hash = sha256Hex(bytes);
    const put = await app.inject({
      method: "PUT",
      url: `/sync/blob/${hash}?device_id=dev-1`,
      headers: {
        authorization: "Bearer tok",
        "x-zetteldraw-schema": "4",
        "content-type": "application/octet-stream",
      },
      payload: bytes,
    });
    expect(put.statusCode).toBe(200);
    expect(put.json()).toMatchObject({ ok: true, hash, bytes: bytes.length });
    expect(storage.map.get(hash)?.equals(bytes)).toBe(true);

    const get = await app.inject({
      method: "GET",
      url: `/sync/blob/${hash}`,
      headers: {
        authorization: "Bearer tok",
        "x-zetteldraw-schema": "4",
      },
    });
    expect(get.statusCode).toBe(200);
    expect(String(get.headers["content-type"])).toContain("application/octet-stream");
    expect(Buffer.from(get.rawPayload).equals(bytes)).toBe(true);
    await app.close();
  });

  it("PUT rejects hash mismatch", async () => {
    const storage = new MemoryStorage();
    const app = testApp(storage);
    await app.ready();
    const bytes = Buffer.from("hello ink");
    const wrong = createHash("sha256").update("other").digest("hex");
    const put = await app.inject({
      method: "PUT",
      url: `/sync/blob/${wrong}?device_id=dev-1`,
      headers: {
        authorization: "Bearer tok",
        "x-zetteldraw-schema": "4",
        "content-type": "application/octet-stream",
      },
      payload: bytes,
    });
    expect(put.statusCode).toBe(400);
    await app.close();
  });

  it("INLINE_BLOB_MAX_BYTES is 256KiB", () => {
    expect(INLINE_BLOB_MAX_BYTES).toBe(256 * 1024);
  });

  it("unauthenticated PUT/GET on registered blob routes return 401 not 404", async () => {
    const app = testApp(new MemoryStorage());
    await app.ready();
    const hash = "0".repeat(64);
    const put = await app.inject({
      method: "PUT",
      url: `/sync/blob/${hash}?device_id=dev-1`,
      headers: { "content-type": "application/octet-stream" },
      payload: Buffer.from("x"),
    });
    expect(put.statusCode).toBe(401);
    const get = await app.inject({ method: "GET", url: `/sync/blob/${hash}` });
    expect(get.statusCode).toBe(401);
    await app.close();
  });

  it("unauthenticated unknown /sync path returns 404 (not masked as 401)", async () => {
    const app = testApp(new MemoryStorage());
    await app.ready();
    const res = await app.inject({ method: "PUT", url: "/sync/no-such-route" });
    expect(res.statusCode).toBe(404);
    await app.close();
  });
});
