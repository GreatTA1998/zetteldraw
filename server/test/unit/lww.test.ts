import { describe, expect, it } from "vitest";
import { needsConflictCopy, resolveBoard, resolveLog, resolveNotebook, type BoardRow, type LogRow } from "../../src/lww.js";

const ID = "11111111-1111-4111-8111-111111111111";
const HASH_A = "a".repeat(64);
const HASH_B = "b".repeat(64);

function board(over: Partial<BoardRow> = {}): BoardRow {
  return {
    id: ID,
    notebook_id: null,
    position: "a0",
    ink_hash: HASH_A,
    ink_bytes: 10,
    thumb_hash: null,
    conflict_of: null,
    created_at: 1,
    updated_at: 100,
    deleted_at: null,
    rev: 5,
    ...over,
  };
}

function incoming(over: Partial<BoardRow> = {}, baseRev = 5) {
  const { rev: _rev, ...row } = board(over);
  return { ...row, base_rev: baseRev };
}

const newId = () => "22222222-2222-4222-8222-222222222222";

describe("resolveBoard", () => {
  it("inserts a board the server has never seen", () => {
    const d = resolveBoard(null, incoming({}, 0), newId);
    expect(d.status).toBe("applied");
    expect(d.write?.id).toBe(ID);
    expect(d.copy).toBeNull();
  });

  it("applies a push based on the current rev", () => {
    const d = resolveBoard(board(), incoming({ ink_hash: HASH_B, updated_at: 50 }, 5), newId);
    expect(d.status).toBe("applied");
    expect(d.write?.ink_hash).toBe(HASH_B);
  });

  it("treats a retried, already-applied push as applied without writing", () => {
    const d = resolveBoard(board({ rev: 9 }), incoming({}, 5), newId);
    expect(d).toEqual({ status: "applied", write: null, restamp: false, copy: null });
  });

  it("newer stale push wins and keeps the server's ink as a hidden conflict copy", () => {
    const d = resolveBoard(board({ rev: 9 }), incoming({ ink_hash: HASH_B, updated_at: 200 }, 5), newId);
    expect(d.status).toBe("conflict_won");
    expect(d.write?.ink_hash).toBe(HASH_B);
    expect(d.copy).toMatchObject({ id: newId(), conflict_of: ID, ink_hash: HASH_A, deleted_at: null });
    expect(d.copy).not.toHaveProperty("rev");
  });

  it("older stale push loses, is kept as a conflict copy, and the winner is re-stamped", () => {
    const d = resolveBoard(board({ rev: 9 }), incoming({ ink_hash: HASH_B, updated_at: 50 }, 5), newId);
    expect(d.status).toBe("conflict_lost");
    expect(d.write).toBeNull();
    expect(d.restamp).toBe(true);
    expect(d.copy).toMatchObject({ conflict_of: ID, ink_hash: HASH_B });
  });

  it("server wins a tie on updated_at", () => {
    const d = resolveBoard(board({ rev: 9 }), incoming({ ink_hash: HASH_B, updated_at: 100 }, 5), newId);
    expect(d.status).toBe("conflict_lost");
  });

  it("no copy when both sides carry the same ink (e.g. only moved)", () => {
    const d = resolveBoard(board({ rev: 9 }), incoming({ position: "a1", updated_at: 200 }, 5), newId);
    expect(d.status).toBe("conflict_won");
    expect(d.copy).toBeNull();
  });

  it("an edit that loses to a delete keeps its ink as a copy", () => {
    const d = resolveBoard(
      board({ rev: 9, ink_hash: null, deleted_at: 300, updated_at: 300 }),
      incoming({ ink_hash: HASH_B, updated_at: 200 }, 5),
      newId,
    );
    expect(d.status).toBe("conflict_lost");
    expect(d.copy?.ink_hash).toBe(HASH_B);
  });

  it("a losing delete needs no copy", () => {
    const d = resolveBoard(
      board({ rev: 9, updated_at: 300 }),
      incoming({ ink_hash: null, deleted_at: 200, updated_at: 200 }, 5),
      newId,
    );
    expect(d.status).toBe("conflict_lost");
    expect(d.copy).toBeNull();
  });
});

describe("needsConflictCopy", () => {
  it("only when the loser has live ink the winner lacks", () => {
    const { rev: _r, ...a } = board();
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

  it("shelves the other log whole and does not split it", () => {
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
});
