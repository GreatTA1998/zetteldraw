import { describe, expect, it } from "vitest";
import { planMove, positionBetween, type Placed } from "../../src/order.js";
import { BadRequest } from "../../src/validate.js";
import { parseMoveBody } from "../../src/web.js";

const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, "0")}`;
const page = (n: number, position: string): Placed => ({ id: id(n), position });

function order(pages: Placed[]): string[] {
  return [...pages].sort((a, b) => (a.position < b.position ? -1 : a.position > b.position ? 1 : a.id < b.id ? -1 : 1)).map((p) => p.id);
}

function apply(pages: Placed[], moved: string, position: string): Placed[] {
  return pages.map((p) => (p.id === moved ? { ...p, position } : p));
}

describe("positionBetween", () => {
  it("matches the keys the app's Positions.java produces", () => {
    expect(positionBetween(null, null)).toBe("a0");
    expect(positionBetween("a0", null)).toBe("a1");
    expect(positionBetween(null, "a0")).toBe("Zz");
    expect(positionBetween("a0", "a1")).toBe("a0V");
    expect(positionBetween("a0V", "a1")).toBe("a0l");
    expect(positionBetween("az", null)).toBe("b00");
  });

  it("always sorts strictly between by byte order", () => {
    let lo = "a0";
    let hi = "a1";
    for (let i = 0; i < 50; i++) {
      const mid = positionBetween(lo, hi);
      expect(lo < mid && mid < hi).toBe(true);
      if (i % 2) lo = mid;
      else hi = mid;
    }
  });
});

describe("planMove", () => {
  const list = [page(1, "a0"), page(2, "a1"), page(3, "a2"), page(4, "a3")];

  it("moves a page forward within the notebook, touching only that page", () => {
    const plan = planMove(list, { ...list[0], inTarget: true }, { kind: "after", id: id(3) });
    expect(plan.kind).toBe("write");
    if (plan.kind !== "write") return;
    expect(order(apply(list, id(1), plan.position))).toEqual([id(2), id(3), id(1), id(4)]);
  });

  it("moves a page to the start and to the end", () => {
    const toStart = planMove(list, { ...list[3], inTarget: true }, { kind: "start" });
    expect(toStart.kind === "write" && order(apply(list, id(4), toStart.position))).toEqual([id(4), id(1), id(2), id(3)]);
    const toEnd = planMove(list, { ...list[0], inTarget: true }, { kind: "end" });
    expect(toEnd.kind === "write" && order(apply(list, id(1), toEnd.position))).toEqual([id(2), id(3), id(4), id(1)]);
  });

  it("is a no-op when the page is already there", () => {
    expect(planMove(list, { ...list[2], inTarget: true }, { kind: "after", id: id(2) })).toEqual({ kind: "noop" });
    expect(planMove(list, { ...list[0], inTarget: true }, { kind: "start" })).toEqual({ kind: "noop" });
    expect(planMove(list, { ...list[3], inTarget: true }, { kind: "end" })).toEqual({ kind: "noop" });
  });

  it("places a page from another notebook", () => {
    const incoming = { ...page(9, "a0"), inTarget: false };
    const plan = planMove(list, incoming, { kind: "after", id: id(1) });
    expect(plan.kind).toBe("write");
    if (plan.kind !== "write") return;
    expect(order([...list, { id: id(9), position: plan.position }])).toEqual([id(1), id(9), id(2), id(3), id(4)]);
    expect(planMove([], incoming, { kind: "end" })).toEqual({ kind: "write", position: "a0" });
  });

  it("rejects an anchor that is not in the target list or is the page itself", () => {
    expect(planMove(list, { ...list[0], inTarget: true }, { kind: "after", id: id(42) })).toEqual({ kind: "bad_anchor" });
    expect(planMove(list, { ...list[0], inTarget: true }, { kind: "after", id: id(1) })).toEqual({ kind: "bad_anchor" });
  });

  it("goes after a tie group when two pages share a key", () => {
    const tied = [page(1, "a0"), page(2, "a1"), page(3, "a1"), page(4, "a2")];
    const plan = planMove(tied, { ...page(9, "a5"), inTarget: false }, { kind: "after", id: id(2) });
    expect(plan.kind).toBe("write");
    if (plan.kind !== "write") return;
    expect(plan.position > "a1" && plan.position < "a2").toBe(true);
  });

  it("keeps a long run of drags between the same neighbours valid", () => {
    let pages = [page(1, "a0"), page(2, "a1"), page(3, "a2")];
    for (let i = 0; i < 30; i++) {
      const [first, , last] = order(pages);
      const plan = planMove(pages, { ...pages.find((p) => p.id === last)!, inTarget: true }, { kind: "after", id: first });
      expect(plan.kind).toBe("write");
      if (plan.kind === "write") pages = apply(pages, last, plan.position);
      expect(order(pages)).toEqual([first, last, order(pages)[2]]);
      expect(order(pages)[1]).toBe(last);
    }
  });
});

describe("parseMoveBody", () => {
  it("reads notebook ids, the scratchpad and the anchor", () => {
    expect(parseMoveBody({ notebook_id: id(1).toUpperCase() })).toEqual({ notebookId: id(1), target: { kind: "end" } });
    expect(parseMoveBody({ notebook_id: null, after_id: null })).toEqual({ notebookId: null, target: { kind: "start" } });
    expect(parseMoveBody({ notebook_id: "scratchpad", after_id: id(2) })).toEqual({
      notebookId: null,
      target: { kind: "after", id: id(2) },
    });
  });

  it("rejects malformed bodies", () => {
    expect(() => parseMoveBody(null)).toThrow(BadRequest);
    expect(() => parseMoveBody({})).toThrow(BadRequest);
    expect(() => parseMoveBody({ notebook_id: "nope" })).toThrow(BadRequest);
    expect(() => parseMoveBody({ notebook_id: null, after_id: 3 })).toThrow(BadRequest);
  });
});
