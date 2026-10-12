import { describe, expect, it } from "vitest";
import type pg from "pg";
import { backupStatus } from "../../src/web.js";
import type { InkStorage } from "../../src/storage.js";

function fakePool(responses: Array<{ rows: unknown[] }>): pg.Pool {
  let i = 0;
  return {
    query: async () => {
      const next = responses[i++] ?? { rows: [] };
      return next;
    },
  } as unknown as pg.Pool;
}

function fakeStorage(present: Set<string>): InkStorage {
  return {
    has: async (hash) => present.has(hash),
    put: async () => {},
    get: async (hash) => {
      if (!present.has(hash)) {
        throw new Error("missing");
      }
      return Buffer.from(hash);
    },
  };
}

describe("backupStatus", () => {
  it("is ready when every board and notebook_log ink blob exists", async () => {
    const hash = "ab".repeat(32);
    const pool = fakePool([
      { rows: [{ n: 1 }] },
      { rows: [{ id: "b1", ink_hash: hash }] },
      { rows: [{ id: "l1", ink_hash: hash }] },
    ]);
    const status = await backupStatus(pool, fakeStorage(new Set([hash])), {
      kind: "user",
      userId: "google-sub",
    });
    expect(status.ok).toBe(true);
    expect(status.ready_for_device_restore).toBe(true);
    expect(status.boards_with_ink).toBe(1);
    expect(status.logs_with_ink).toBe(1);
    expect(status.missing).toEqual([]);
  });

  it("fails when a notebook_log blob is missing even if boards look fine", async () => {
    const boardHash = "cd".repeat(32);
    const logHash = "ef".repeat(32);
    const pool = fakePool([
      { rows: [{ n: 1 }] },
      { rows: [{ id: "b1", ink_hash: boardHash }] },
      { rows: [{ id: "l1", ink_hash: logHash }] },
    ]);
    const status = await backupStatus(pool, fakeStorage(new Set([boardHash])), {
      kind: "user",
      userId: "google-sub",
    });
    expect(status.ok).toBe(false);
    expect(status.ready_for_device_restore).toBe(false);
    expect(status.missing).toEqual([{ table: "notebook_logs", id: "l1", ink_hash: logHash }]);
  });
});
