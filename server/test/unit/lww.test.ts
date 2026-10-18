import { describe, expect, it } from "vitest";
import { needsConflictCopy, resolveLink, resolveLog, resolveNotebook, type LogRow } from "../../src/lww.js";

const ID = "11111111-1111-4111-8111-111111111111";
const HASH_A = "a".repeat(64);
const HASH_B = "b".repeat(64);
const newId = () => "22222222-2222-4222-8222-222222222222";

describe("needsConflictCopy", () => {
  it("only when the loser has live ink the winner lacks", () => {
    const a = { ink_hash: HASH_A, deleted_at: null as number | null };
    expect(needsConflictCopy(a, { ...a, ink_hash: HASH_B })).toBe(true);
    expect(needsConflictCopy(a, a)).toBe(false);
    expect(needsConflictCopy({ ...a, ink_hash: null }, a)).toBe(false);
    expect(needsConflictCopy(a, { ...a, deleted_at: 1 })).toBe(true);
  });
});

describe("resolveLog", () => {
  function log(over: Partial<LogRow> = {}): LogRow {
    return {
      id: ID,
      notebook_id: null,
      ink_hash: HASH_A,
      ink_bytes: 40,
      slice_height: 1420,
      conflict_of: null,
      created_at: 1,
      updated_at: 100,
      deleted_at: null,
      rev: 5,
      ...over,
    };
  }

  it("exception path: shelves the other log whole and does not split it", () => {
    const { rev: _rev, ...row } = log({ ink_hash: HASH_B, ink_bytes: 80, updated_at: 200 });
    const won = resolveLog(log({ rev: 9 }), { ...row, base_rev: 4 }, newId);
    expect(won.status).toBe("conflict_won");
    expect(won.write?.ink_hash).toBe(HASH_B);
    expect(won.copy).toMatchObject({
      id: newId(),
      conflict_of: ID,
      ink_hash: HASH_A,
      ink_bytes: 40,
      slice_height: 1420,
      deleted_at: null,
    });
    expect(won.copy).not.toHaveProperty("rev");

    const lost = resolveLog(log({ rev: 9 }), { ...row, updated_at: 50, base_rev: 4 }, newId);
    expect(lost.status).toBe("conflict_lost");
    expect(lost.write).toBeNull();
    expect(lost.restamp).toBe(true);
    expect(lost.copy?.ink_hash).toBe(HASH_B);
    expect(lost.copy?.ink_bytes).toBe(80);
  });

  it("refuses a shorter live incoming even with a newer clock or matching base_rev", () => {
    const existing = log({ rev: 9, ink_bytes: 17_000_000, ink_hash: HASH_A, updated_at: 100 });
    const { rev: _rev, ...shortRow } = log({
      ink_hash: HASH_B,
      ink_bytes: 500_000,
      updated_at: 999_999,
    });
    const refused = resolveLog(existing, { ...shortRow, base_rev: 9 }, newId);
    expect(refused.status).toBe("conflict_lost");
    expect(refused.write).toBeNull();
    expect(refused.restamp).toBe(true);
    expect(refused.copy).toMatchObject({
      conflict_of: ID,
      ink_hash: HASH_B,
      ink_bytes: 500_000,
    });

    const refusedLww = resolveLog(existing, { ...shortRow, base_rev: 4 }, newId);
    expect(refusedLww.status).toBe("conflict_lost");
    expect(refusedLww.write).toBeNull();
  });
});

describe("resolveNotebook", () => {
  const parent = "33333333-3333-4333-8333-333333333333";
  const nb = {
    id: ID,
    title: "comedy",
    position: "a0",
    parent_id: null as string | null,
    created_at: 1,
    updated_at: 100,
    deleted_at: null,
    rev: 3,
  };
  it("LWW without copies", () => {
    expect(resolveNotebook(nb, { ...nb, title: "jokes", updated_at: 200, base_rev: 1 }).status).toBe("conflict_won");
    expect(resolveNotebook(nb, { ...nb, title: "jokes", updated_at: 50, base_rev: 1 })).toMatchObject({
      status: "conflict_lost",
      restamp: true,
      copy: null,
    });
    expect(resolveNotebook(nb, { ...nb, base_rev: 1 }).status).toBe("applied");
  });

  it("treats a parent change as a real change", () => {
    const stored = { ...nb, rev: 9 };
    const won = resolveNotebook(stored, { ...stored, parent_id: parent, updated_at: 200, base_rev: 1 });
    expect(won.status).toBe("conflict_won");
    expect(won.write?.parent_id).toBe(parent);
  });

  it("keeps the stored parent when a newer push omits parent_id", () => {
    const stored = { ...nb, parent_id: parent, rev: 9 };
    const { parent_id: _drop, rev: _rev, ...flat } = stored;
    const won = resolveNotebook(stored, { ...flat, title: "jokes", updated_at: 200, base_rev: 1 });
    expect(won.status).toBe("conflict_won");
    expect(won.write?.parent_id).toBe(parent);
    expect(won.write?.title).toBe("jokes");
  });

  it("rejects untouched seed placeholders that would clobber a server notebook", () => {
    const stored = { ...nb, rev: 0, updated_at: 0, position: "a0" };
    const seed = {
      ...stored,
      position: "a0V",
      updated_at: 0,
      base_rev: 0,
    };
    expect(resolveNotebook(stored, seed)).toMatchObject({
      status: "conflict_lost",
      restamp: true,
      write: null,
    });
  });
});

describe("resolveLink", () => {
  const link = {
    id: ID,
    source_id: "11111111-1111-4111-8111-111111111111",
    target_id: "22222222-2222-4222-8222-222222222222",
    created_at: 1,
    updated_at: 100,
    deleted_at: null,
    rev: 3,
  };
  it("is last-write-wins and keeps no conflict copy", () => {
    expect(resolveLink(null, { ...link, base_rev: 0 }).status).toBe("applied");
    expect(resolveLink(link, { ...link, deleted_at: 200, updated_at: 200, base_rev: 1 }).status).toBe("conflict_won");
    expect(resolveLink(link, { ...link, deleted_at: 50, updated_at: 50, base_rev: 1 })).toMatchObject({
      status: "conflict_lost",
      restamp: true,
      copy: null,
    });
    expect(resolveLink(link, { ...link, base_rev: 1 }).status).toBe("applied");
  });
});
