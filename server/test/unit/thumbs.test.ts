import { describe, expect, it } from "vitest";
import { demoLibrary } from "../../src/demo.js";
import { decodeInk, encodeInk, InkFormatError, strokeWidths, BASE_WIDTH_PX, type InkStroke } from "../../src/ink.js";
import { pageBox, renderPng, DEFAULT_PAGE } from "../../src/render.js";
import type { InkStorage, ObjectStore } from "../../src/storage.js";
import { sha256Hex } from "../../src/sync.js";
import { imageKey, Thumbnailer, IMAGE_WIDTH } from "../../src/thumbs.js";

function pngSize(png: Buffer): { width: number; height: number } {
  expect(png.subarray(1, 4).toString("latin1")).toBe("PNG");
  return { width: png.readUInt32BE(16), height: png.readUInt32BE(20) };
}

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

class MemoryStore implements InkStorage, ObjectStore {
  objects = new Map<string, Buffer>();
  puts = 0;
  async has(hash: string) {
    return this.objects.has(`ink/${hash}`);
  }
  async put(hash: string, bytes: Buffer) {
    this.objects.set(`ink/${hash}`, bytes);
  }
  async get(hash: string) {
    return this.objects.get(`ink/${hash}`)!;
  }
  async hasObject(key: string) {
    return this.objects.has(key);
  }
  async putObject(key: string, bytes: Buffer) {
    this.puts++;
    this.objects.set(key, bytes);
  }
  async getObject(key: string) {
    return this.objects.get(key) ?? null;
  }
}

describe("ink codec", () => {
  it("round-trips the app's .zdi format", () => {
    const strokes = [line(10, 20, 300, 40), line(50, 500, 60, 900, 3)];
    strokes[1][2].tiltX = -12;
    const decoded = decodeInk(encodeInk(strokes));
    expect(decoded).toHaveLength(2);
    expect(decoded[1][2]).toEqual({ ...strokes[1][2], x: Math.fround(strokes[1][2].x), y: Math.fround(strokes[1][2].y) });
    expect(decoded[0][19].t - decoded[0][0].t).toBe(152);
  });

  it("decodes a file the Java codec wrote (big-endian, zlib)", () => {
    // InkCodec.encode of one stroke with one point (x=1.5, y=2.5, p=0.25, size=1, tilt 3/-4, t=1000).
    const hex = "5a44490178016360606064806020c5fcc2fe000383830203835d0303833d103330ffff0324190054bb05c8";
    const stroke = decodeInk(Buffer.from(hex, "hex"));
    expect(stroke).toEqual([[{ x: 1.5, y: 2.5, pressure: 0.25, size: 1, tiltX: 3, tiltY: -4, t: 1000 }]]);
  });

  it("rejects other files", () => {
    expect(() => decodeInk(Buffer.from("nope"))).toThrow(InkFormatError);
    expect(() => decodeInk(Buffer.from([0x5a, 0x44, 0x49, 9, 0]))).toThrow(/version 9/);
  });

  it("tapers both ends like InkRenderer", () => {
    const w = strokeWidths(line(0, 0, 400, 0, 41));
    expect(w[20]).toBeCloseTo(BASE_WIDTH_PX);
    expect(w[0]).toBeCloseTo(BASE_WIDTH_PX * 0.32);
    expect(w[40]).toBeCloseTo(BASE_WIDTH_PX * 0.32);
  });
});

describe("render", () => {
  it("renders at the requested width with the page aspect", () => {
    const png = renderPng([line(100, 100, 1000, 1500)], 480);
    expect(pngSize(png)).toEqual({ width: 480, height: Math.round((DEFAULT_PAGE.height * 480) / DEFAULT_PAGE.width) });
  });

  it("grows the page box for ink beyond the default page instead of cropping", () => {
    expect(pageBox([line(0, 0, 100, 100)])).toEqual(DEFAULT_PAGE);
    const box = pageBox([line(0, 0, 2000, 100)]);
    expect(box.width).toBeGreaterThanOrEqual(2024);
    expect(box.width / box.height).toBeCloseTo(DEFAULT_PAGE.width / DEFAULT_PAGE.height, 2);
  });

  it("renders an empty page", () => {
    expect(pngSize(renderPng([], 100)).width).toBe(100);
  });
});

describe("Thumbnailer", () => {
  it("renders once, stores under the ink hash and serves the cached copy", async () => {
    const store = new MemoryStore();
    const ink = encodeInk([line(10, 10, 800, 900)]);
    const hash = sha256Hex(ink);
    await store.put(hash, ink);
    const thumbs = new Thumbnailer(store);

    const [a, b] = await Promise.all([thumbs.image("thumb", hash), thumbs.image("thumb", hash)]);
    expect(a).not.toBeNull();
    expect(a).toBe(b);
    expect(store.puts).toBe(1);
    expect(store.objects.get(imageKey("thumb", hash))).toEqual(a);
    expect(pngSize(a!).width).toBe(IMAGE_WIDTH.thumb);
    expect(await thumbs.ensureThumb(hash)).toBe(false);

    const full = await thumbs.image("render", hash);
    expect(pngSize(full!).width).toBe(IMAGE_WIDTH.render);
    expect(store.puts).toBe(2);
  });

  it("returns null for ink it does not have", async () => {
    expect(await new Thumbnailer(new MemoryStore()).image("thumb", "0".repeat(64))).toBeNull();
  });

  it("renders enqueued thumbnails in the background and reports failures", async () => {
    const store = new MemoryStore();
    const good = encodeInk([line(0, 0, 50, 50)]);
    const bad = Buffer.from("ZDI\u0001garbage", "latin1");
    await store.put(sha256Hex(good), good);
    await store.put(sha256Hex(bad), bad);
    const errors: string[] = [];
    const thumbs = new Thumbnailer(store, DEFAULT_PAGE, (_err, h) => errors.push(h));
    thumbs.enqueue(sha256Hex(bad));
    thumbs.enqueue(sha256Hex(good));
    thumbs.enqueue(sha256Hex(good));
    await thumbs.idle();
    expect(errors).toEqual([sha256Hex(bad)]);
    expect(await store.hasObject(imageKey("thumb", sha256Hex(good)))).toBe(true);
    expect(store.puts).toBe(1);
  });
});

describe("demo library", () => {
  it("is deterministic and valid", () => {
    const a = demoLibrary();
    const b = demoLibrary();
    expect(a.boards.map((x) => x.ink_hash)).toEqual(b.boards.map((x) => x.ink_hash));
    for (const board of a.boards) {
      if (board.ink_hash) {
        const bytes = a.blobs.get(board.ink_hash)!;
        expect(sha256Hex(bytes)).toBe(board.ink_hash);
        expect(decodeInk(bytes).length).toBeGreaterThan(0);
      }
    }
    expect(new Set(a.boards.map((x) => x.id)).size).toBe(a.boards.length);
  });
});
