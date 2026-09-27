import { describe, expect, it } from "vitest";
import { listMigrations } from "../../src/db.js";
import { BadRequest, parsePushBody } from "../../src/validate.js";

describe("parsePushBody", () => {
  it("accepts a well-formed push and lowercases ids", () => {
    const body = parsePushBody({
      schema_version: 1,
      device_id: "d",
      notebooks: [],
      boards: [
        {
          id: "AAAAAAAA-1111-4111-8111-111111111111",
          notebook_id: null,
          position: "a0",
          ink_hash: null,
          ink_bytes: 0,
          thumb_hash: null,
          conflict_of: null,
          created_at: 1,
          updated_at: 2,
          deleted_at: null,
          base_rev: 0,
        },
      ],
      blobs: {},
    });
    expect(body.boards[0].id).toBe("aaaaaaaa-1111-4111-8111-111111111111");
  });

  it("rejects bad ids and positions", () => {
    expect(() => parsePushBody({ schema_version: 1, boards: [{ id: "nope" }] })).toThrow(BadRequest);
    expect(() =>
      parsePushBody({
        schema_version: 1,
        notebooks: [
          {
            id: "aaaaaaaa-1111-4111-8111-111111111111",
            title: "x",
            position: "a 0",
            created_at: 1,
            updated_at: 1,
            deleted_at: null,
            base_rev: 0,
          },
        ],
      }),
    ).toThrow(BadRequest);
  });
});

describe("migrations", () => {
  it("are numbered without gaps; schema_version is the highest", async () => {
    const dir = new URL("../../migrations", import.meta.url).pathname;
    const migrations = await listMigrations(dir);
    expect(migrations.map((m) => m.version)).toEqual(migrations.map((_, i) => i + 1));
    expect(migrations.length).toBeGreaterThanOrEqual(1);
  });
});
