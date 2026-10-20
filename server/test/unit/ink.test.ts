import { describe, expect, it } from "vitest";
import {
  decodeInk,
  decodeInkFile,
  encodeInk,
  InkFormatError,
  strokeWidths,
  BASE_WIDTH_PX,
  type InkStroke,
} from "../../src/ink.js";

const line = (x1: number, y1: number, x2: number, y2: number, n = 20): InkStroke =>
  Array.from({ length: n }, (_, i) => ({
    x: x1 + ((x2 - x1) * i) / (n - 1),
    y: y1 + ((y2 - y1) * i) / (n - 1),
    pressure: 0.5,
    size: 1,
    tiltX: 0,
    tiltY: 0,
    t: 1_700_000_000_000 + i * 8,
  }));

const ids = ["0f8fad5b-d9cb-469f-a165-70867728950e", "7c9e6679-7425-40de-944b-e07fc1f90ae7"];

describe("ink codec", () => {
  it.each([1, 2, 3] as const)("round-trips version %i of the app's .zdi format", (version) => {
    const strokes = [line(10, 20, 300, 40), line(50, 500, 60, 900, 3)];
    strokes[1][2].tiltX = -12;
    const colors = [0xffe53935, 0xff9ed9c8];
    const file = decodeInkFile(encodeInk(strokes, { version, ids, colors }));
    expect(file.version).toBe(version);
    expect(file.ids).toEqual(version >= 2 ? ids : [null, null]);
    expect(file.colors).toEqual(version >= 3 ? colors : [0xff000000, 0xff000000]);
    const decoded = file.strokes;
    expect(decoded).toHaveLength(2);
    expect(decoded[1][2]).toEqual({
      ...strokes[1][2],
      x: Math.fround(strokes[1][2].x),
      y: Math.fround(strokes[1][2].y),
    });
    expect(decoded[0][19].t - decoded[0][0].t).toBe(152);
  });

  it("writes version 3 by default and needs an id per stroke for it", () => {
    expect(encodeInk([line(0, 0, 10, 10)], { ids: [ids[0]] })[3]).toBe(3);
    expect(() => encodeInk([line(0, 0, 10, 10)])).toThrow(InkFormatError);
  });

  it("decodes a version 2 file the Java codec wrote (stroke ids before the point count)", () => {
    const hex =
      "5a444902780163606060e2ef5f1b7df3b4dbfc85a9056de51a53f918181818811808985fd81f606070506060b06b6060b0076206e6ff7f802443cdbcb4ca1255877b53bc1fd41ffcc9f51c2804d3f312a41e9b1e00120e1af7";
    const file = decodeInkFile(Buffer.from(hex, "hex"));
    expect(file.version).toBe(2);
    expect(file.ids).toEqual(ids);
    expect(file.strokes).toEqual([
      [{ x: 1.5, y: 2.5, pressure: 0.25, size: 1, tiltX: 3, tiltY: -4, t: 1000 }],
      [{ x: 2.5, y: 2.5, pressure: 0.25, size: 1, tiltX: 3, tiltY: -4, t: 1001 }],
    ]);
  });

  it("decodes a version 1 file the Java codec wrote (big-endian, zlib)", () => {
    const hex = "5a44490178016360606064806020c5fcc2fe000383830203835d0303833d103330ffff0324190054bb05c8";
    const stroke = decodeInk(Buffer.from(hex, "hex"));
    expect(stroke).toEqual([[{ x: 1.5, y: 2.5, pressure: 0.25, size: 1, tiltX: 3, tiltY: -4, t: 1000 }]]);
  });

  it("rejects other files", () => {
    expect(() => decodeInk(Buffer.from("nope"))).toThrow(InkFormatError);
    expect(() => decodeInk(Buffer.from([0x5a, 0x44, 0x49, 4, 0]))).toThrow(/version 4/);
  });

  it("tapers both ends like InkRenderer", () => {
    const w = strokeWidths(line(0, 0, 400, 0, 41));
    expect(w[20]).toBeCloseTo(BASE_WIDTH_PX);
    expect(w[0]).toBeCloseTo(BASE_WIDTH_PX * 0.32);
    expect(w[40]).toBeCloseTo(BASE_WIDTH_PX * 0.32);
  });
});
