import { describe, expect, it } from "vitest";
import { readSliceIndex, ZdlFormatError } from "../../src/zdl.js";

/** Build a minimal ZDL: magic+ver, sliceHeight, header slices, then raw ops. */
function zdl(
  sliceHeight: number,
  header: Array<{ id: string; height: number }>,
  ops: Buffer[] = [],
): Buffer {
  const parts: Buffer[] = [];
  parts.push(Buffer.from([0x5a, 0x44, 0x4c, 1]));
  const head = Buffer.alloc(8);
  head.writeInt32BE(sliceHeight, 0);
  head.writeInt32BE(header.length, 4);
  parts.push(head);
  for (const slice of header) {
    const id = Buffer.from(slice.id, "utf8");
    const row = Buffer.alloc(4 + 2 + id.length);
    row.writeInt32BE(slice.height, 0);
    row.writeUInt16BE(id.length, 4);
    id.copy(row, 6);
    parts.push(row);
  }
  for (const op of ops) {
    parts.push(op);
  }
  return Buffer.concat(parts);
}

function op(code: number, payload: Buffer): Buffer {
  const buf = Buffer.alloc(5 + payload.length);
  buf[0] = code;
  buf.writeInt32BE(payload.length, 1);
  payload.copy(buf, 5);
  return buf;
}

function idPayload(id: string): Buffer {
  const raw = Buffer.from(id, "utf8");
  const buf = Buffer.alloc(2 + raw.length);
  buf.writeUInt16BE(raw.length, 0);
  raw.copy(buf, 2);
  return buf;
}

describe("readSliceIndex", () => {
  it("reads header slices", () => {
    const bytes = zdl(1407, [
      { id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", height: 1407 },
      { id: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", height: 1295 },
    ]);
    expect(readSliceIndex(bytes)).toEqual([
      { id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", height: 1407 },
      { id: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", height: 1295 },
    ]);
  });

  it("applies EXTEND after empty header (Create new page)", () => {
    const id = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    const payload = Buffer.concat([idPayload(id), (() => {
      const h = Buffer.alloc(4);
      h.writeInt32BE(1407, 0);
      return h;
    })()]);
    const bytes = zdl(1407, [], [op(5, payload)]);
    expect(readSliceIndex(bytes)).toEqual([{ id, height: 1407 }]);
  });

  it("applies TEAR delete (not wipe) to drop a slice", () => {
    const a = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    const b = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    const tear = Buffer.alloc(5 + 4);
    tear[0] = 1; // TEAR_DELETE
    tear.writeInt32BE(0, 1);
    tear.writeInt32BE(0, 5); // empty newIds
    const bytes = zdl(
      1407,
      [
        { id: a, height: 1407 },
        { id: b, height: 1407 },
      ],
      [op(4, tear)],
    );
    expect(readSliceIndex(bytes)).toEqual([{ id: b, height: 1407 }]);
  });

  it("skips stroke ops by length", () => {
    const id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    const junk = Buffer.alloc(32, 7);
    const bytes = zdl(1407, [{ id, height: 1407 }], [op(1, junk), op(2, junk)]);
    expect(readSliceIndex(bytes)).toEqual([{ id, height: 1407 }]);
  });

  it("rejects non-ZDL", () => {
    expect(() => readSliceIndex(Buffer.from("ZDI\x01"))).toThrow(ZdlFormatError);
  });
});
