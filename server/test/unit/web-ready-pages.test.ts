import { describe, expect, it } from "vitest";
import type pg from "pg";
import { listPages, listNotebooks, placesOf } from "../../src/web.js";
import type { InkStorage } from "../../src/storage.js";
import { readSliceIndex } from "../../src/zdl.js";

function zdlEmptyExtend(pageId: string, height = 1407): Buffer {
  const parts: Buffer[] = [];
  parts.push(Buffer.from([0x5a, 0x44, 0x4c, 1]));
  const head = Buffer.alloc(8);
  head.writeInt32BE(height, 0);
  head.writeInt32BE(0, 4);
  parts.push(head);
  const id = Buffer.from(pageId, "utf8");
  const payload = Buffer.alloc(2 + id.length + 4);
  payload.writeUInt16BE(id.length, 0);
  id.copy(payload, 2);
  payload.writeInt32BE(height, 2 + id.length);
  const op = Buffer.alloc(5 + payload.length);
  op[0] = 5;
  op.writeInt32BE(payload.length, 1);
  payload.copy(op, 5);
  parts.push(op);
  return Buffer.concat(parts);
}

describe("web Ready page list", () => {
  it("lists pages from Ready ZDL (no boards SQL)", async () => {
    const notebookId = "11111111-1111-1111-1111-111111111111";
    const pageId = "22222222-2222-2222-2222-222222222222";
    const hash = "ab".repeat(32);
    const bytes = zdlEmptyExtend(pageId);
    expect(readSliceIndex(bytes)).toHaveLength(1);

    const queries: string[] = [];
    const pool = {
      query: async (sql: string) => {
        queries.push(sql);
        if (sql.includes("FROM notebooks n WHERE n.id")) {
          return {
            rows: [
              {
                id: notebookId,
                title: "log only",
                position: "a0",
                parent_id: null,
                created_at: 1,
                updated_at: 2,
                deleted_at: null,
                rev: 1,
              },
            ],
          };
        }
        if (sql.includes("FROM notebook_logs")) {
          return {
            rows: [
              {
                id: "log1",
                notebook_id: notebookId,
                ink_hash: hash,
                ink_bytes: bytes.length,
                slice_height: 1407,
                conflict_of: null,
                created_at: 1,
                updated_at: 9,
                deleted_at: null,
                rev: 3,
              },
            ],
          };
        }
        if (sql.includes("FROM page_links")) {
          return { rows: [] };
        }
        return { rows: [] };
      },
    } as unknown as pg.Pool;

    const storage: InkStorage = {
      has: async () => true,
      put: async () => {},
      get: async (h) => {
        expect(h).toBe(hash);
        return bytes;
      },
    };

    const pages = await listPages(pool, notebookId, { kind: "user", userId: "u1" }, storage);
    expect(pages).toHaveLength(1);
    expect(pages[0].id).toBe(pageId);
    expect(pages[0].ink_hash).toBeNull();
    expect(queries.some((q) => q.includes("notebook_logs"))).toBe(true);
    expect(queries.some((q) => q.includes("FROM boards"))).toBe(false);
  });

  it("fills page_count from Ready only (no boards JOIN)", async () => {
    const notebookId = "11111111-1111-1111-1111-111111111111";
    const pageId = "22222222-2222-2222-2222-222222222222";
    const hash = "cd".repeat(32);
    const bytes = zdlEmptyExtend(pageId);

    let boardQueries = 0;
    let logQueries = 0;
    const pool = {
      query: async (sql: string) => {
        if (sql.includes("FROM boards")) {
          boardQueries++;
          return { rows: [] };
        }
        if (sql.includes("FROM notebooks n") && sql.includes("ORDER BY n.position")) {
          return {
            rows: [
              {
                id: notebookId,
                title: "art themes",
                position: "a0",
                parent_id: null,
                created_at: 1,
                updated_at: 2,
                deleted_at: null,
                rev: 1,
              },
            ],
          };
        }
        if (sql.includes("FROM notebook_logs")) {
          logQueries++;
          if (sql.includes("l.notebook_id IS NULL")) {
            return { rows: [] };
          }
          return {
            rows: [
              {
                id: "log1",
                notebook_id: notebookId,
                ink_hash: hash,
                ink_bytes: bytes.length,
                slice_height: 1407,
                conflict_of: null,
                created_at: 1,
                updated_at: 9,
                deleted_at: null,
                rev: 3,
              },
            ],
          };
        }
        return { rows: [] };
      },
    } as unknown as pg.Pool;

    const storage: InkStorage = {
      has: async () => true,
      put: async () => {},
      get: async () => bytes,
    };

    const notebooks = await listNotebooks(pool, { kind: "user", userId: "u1" }, storage);
    const art = notebooks.find((n) => n.id === notebookId);
    expect(art?.page_count).toBe(1);
    expect(art?.last_edited_at).toBe(9);
    expect(logQueries).toBeGreaterThan(0);
    expect(boardQueries).toBe(0);
  });

  it("placesOf resolves page ids from Ready logs only", async () => {
    const notebookId = "11111111-1111-1111-1111-111111111111";
    const pageId = "22222222-2222-2222-2222-222222222222";
    const hash = "ef".repeat(32);
    const bytes = zdlEmptyExtend(pageId);

    const queries: string[] = [];
    const pool = {
      query: async (sql: string, params?: unknown[]) => {
        queries.push(sql);
        if (sql.includes("FROM notebook_logs")) {
          return {
            rows: [
              {
                id: "log1",
                notebook_id: notebookId,
                ink_hash: hash,
                ink_bytes: bytes.length,
                slice_height: 1407,
                conflict_of: null,
                created_at: 1,
                updated_at: 9,
                deleted_at: null,
                rev: 3,
              },
            ],
          };
        }
        if (sql.includes("FROM notebooks n") && sql.includes("ANY(")) {
          expect(params?.at(-1)).toEqual([notebookId]);
          return { rows: [{ id: notebookId, title: "art themes" }] };
        }
        return { rows: [] };
      },
    } as unknown as pg.Pool;

    const storage: InkStorage = {
      has: async () => true,
      put: async () => {},
      get: async () => bytes,
    };

    const places = await placesOf(pool, [pageId], { kind: "user", userId: "u1" }, storage);
    expect(places.get(pageId)).toMatchObject({
      notebook_id: notebookId,
      notebook_title: "art themes",
      index: 1,
      count: 1,
    });
    expect(queries.some((q) => q.includes("FROM boards"))).toBe(false);
  });
});
