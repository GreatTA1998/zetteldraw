import { describe, expect, it } from "vitest";
import { positionBetween } from "../../src/order.js";

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
