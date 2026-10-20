import { describe, expect, it } from "vitest";
import { replayZdl, strokesForSlice, ZdlFormatError } from "./zdl-paper";

function zdl(
  sliceHeight: number,
  header: Array<{ id: string; height: number }>,
  ops: Uint8Array[] = [],
): ArrayBuffer {
  const parts: Uint8Array[] = [];
  parts.push(new Uint8Array([0x5a, 0x44, 0x4c, 1]));
  const head = new DataView(new ArrayBuffer(8));
  head.setInt32(0, sliceHeight);
  head.setInt32(4, header.length);
  parts.push(new Uint8Array(head.buffer));
  for (const slice of header) {
    const id = new TextEncoder().encode(slice.id);
    const row = new DataView(new ArrayBuffer(4 + 2 + id.length));
    row.setInt32(0, slice.height);
    row.setUint16(4, id.length);
    new Uint8Array(row.buffer).set(id, 6);
    parts.push(new Uint8Array(row.buffer));
  }
  for (const op of ops) parts.push(op);
  const total = parts.reduce((n, p) => n + p.length, 0);
  const out = new Uint8Array(total);
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.length;
  }
  return out.buffer;
}

function op(code: number, payload: Uint8Array): Uint8Array {
  const buf = new Uint8Array(5 + payload.length);
  buf[0] = code;
  new DataView(buf.buffer).setInt32(1, payload.length);
  buf.set(payload, 5);
  return buf;
}

function idPayload(id: string): Uint8Array {
  const raw = new TextEncoder().encode(id);
  const buf = new Uint8Array(2 + raw.length);
  new DataView(buf.buffer).setUint16(0, raw.length);
  buf.set(raw, 2);
  return buf;
}

function strokePayload(
  id: string,
  points: Array<{ x: number; y: number; w?: number }>,
): Uint8Array {
  const idBytes = idPayload(id);
  const body = new ArrayBuffer(idBytes.length + 12 + points.length * 28 + 4);
  const view = new DataView(body);
  let o = 0;
  new Uint8Array(body).set(idBytes, 0);
  o = idBytes.length;
  view.setInt32(o, points.length);
  o += 4;
  view.setBigInt64(o, BigInt(1000));
  o += 8;
  for (const p of points) {
    view.setFloat32(o, p.x);
    view.setFloat32(o + 4, p.y);
    view.setFloat32(o + 8, 0.5);
    view.setFloat32(o + 12, 1);
    view.setInt16(o + 16, 0);
    view.setInt16(o + 18, 0);
    view.setInt32(o + 20, 0);
    view.setFloat32(o + 24, p.w ?? 5.91);
    o += 28;
  }
  view.setInt32(o, 0xff000000);
  return new Uint8Array(body);
}

describe("replayZdl", () => {
  it("reads header slices", () => {
    const a = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    const b = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    const paper = replayZdl(
      zdl(1407, [
        { id: a, height: 1407 },
        { id: b, height: 1295 },
      ]),
    );
    expect(paper.slices).toEqual([
      { id: a, height: 1407 },
      { id: b, height: 1295 },
    ]);
    expect(paper.strokes).toEqual([]);
  });

  it("applies APPEND and returns page-local strokes", () => {
    const page = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    const strokeId = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    const bytes = zdl(1407, [{ id: page, height: 1407 }], [
      op(1, strokePayload(strokeId, [
        { x: 10, y: 100 },
        { x: 20, y: 200 },
      ])),
    ]);
    const paper = replayZdl(bytes);
    expect(paper.strokes).toHaveLength(1);
    const local = strokesForSlice(paper, 0);
    expect(local[0].points[0]).toMatchObject({ x: 10, y: 100 });
    expect(local[0].widths[0]).toBeCloseTo(5.91);
  });

  it("applies TEAR delete to drop a slice", () => {
    const a = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    const b = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    const tear = new Uint8Array(5 + 4);
    tear[0] = 1; // TEAR_DELETE
    new DataView(tear.buffer).setInt32(1, 0);
    new DataView(tear.buffer).setInt32(5, 0);
    const paper = replayZdl(
      zdl(
        1407,
        [
          { id: a, height: 1407 },
          { id: b, height: 1407 },
        ],
        [op(4, tear)],
      ),
    );
    expect(paper.slices.map((s) => s.id)).toEqual([b]);
  });

  it("rejects non-ZDL", () => {
    expect(() => replayZdl(new Uint8Array([1, 2, 3]).buffer)).toThrow(ZdlFormatError);
  });
});
