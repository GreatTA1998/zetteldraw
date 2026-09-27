import { describe, expect, it } from "vitest";
import { reorder } from "./reorder";

const pages = ["a", "b", "c", "d"].map((id) => ({ id }));
const ids = (r: ReturnType<typeof reorder<{ id: string }>>) => r?.pages.map((p) => p.id);

describe("reorder", () => {
  it("moves forward and anchors after the new predecessor", () => {
    const r = reorder(pages, "a", "c");
    expect(ids(r)).toEqual(["b", "c", "a", "d"]);
    expect(r?.afterId).toBe("c");
  });

  it("moves backward", () => {
    const r = reorder(pages, "d", "b");
    expect(ids(r)).toEqual(["a", "d", "b", "c"]);
    expect(r?.afterId).toBe("a");
  });

  it("anchors null when the page becomes first", () => {
    const r = reorder(pages, "c", "a");
    expect(ids(r)).toEqual(["c", "a", "b", "d"]);
    expect(r?.afterId).toBeNull();
  });

  it("moves to the end", () => {
    expect(reorder(pages, "a", "d")?.afterId).toBe("d");
  });

  it("ignores drops on itself or unknown ids", () => {
    expect(reorder(pages, "b", "b")).toBeNull();
    expect(reorder(pages, "x", "b")).toBeNull();
    expect(reorder(pages, "b", "x")).toBeNull();
  });
});
