import { deflateSync, inflateSync } from "node:zlib";

/**
 * The app's `.zdi` stroke file (InkCodec.java):
 *
 *   "ZDI" u8 version
 *   zlib {
 *     i32 strokeCount
 *     per stroke: [v2+: i64 idMostSig, i64 idLeastSig] i32 pointCount, i64 firstTimestamp
 *       per point: f32 x, f32 y, f32 pressure, f32 size, i16 tiltX, i16 tiltY, i32 dtMillis
 *   }
 *
 * All integers and floats are big-endian (Java DataOutputStream). Coordinates are
 * device pixels on a page the size of the device screen.
 */

/** Version 1 has no stroke ids; version 2 adds a 128-bit id per stroke. Both are still on devices. */
export const INK_VERSIONS = [1, 2] as const;
export type InkVersion = (typeof INK_VERSIONS)[number];
export const INK_VERSION: InkVersion = 2;
const POINT_BYTES = 4 * 4 + 2 * 2 + 4;

export interface InkPoint {
  x: number;
  y: number;
  pressure: number;
  size: number;
  tiltX: number;
  tiltY: number;
  t: number;
}

export type InkStroke = InkPoint[];

export interface InkFile {
  version: InkVersion;
  /** Stroke ids in UUID form, null for version 1 files; parallel to `strokes`. */
  ids: Array<string | null>;
  strokes: InkStroke[];
}

function uuidFromBytes(b: Buffer): string {
  const h = b.toString("hex");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20, 32)}`;
}

function uuidToBytes(id: string): Buffer {
  const hex = id.replace(/-/g, "");
  if (!/^[0-9a-f]{32}$/i.test(hex)) {
    throw new InkFormatError(`stroke id ${id} is not a UUID`);
  }
  return Buffer.from(hex, "hex");
}

export class InkFormatError extends Error {}

export function decodeInk(data: Buffer): InkStroke[] {
  return decodeInkFile(data).strokes;
}

export function decodeInkFile(data: Buffer): InkFile {
  if (data.length < 4 || data.toString("latin1", 0, 3) !== "ZDI") {
    throw new InkFormatError("not a zetteldraw ink file");
  }
  const version = data[3] as InkVersion;
  if (!INK_VERSIONS.includes(version)) {
    throw new InkFormatError(`unsupported ink version ${data[3]}`);
  }
  const hasIds = version >= 2;
  const body = inflateSync(data.subarray(4));
  let off = 0;
  const need = (n: number) => {
    if (off + n > body.length) {
      throw new InkFormatError("truncated ink file");
    }
  };
  need(4);
  const strokeCount = body.readInt32BE(off);
  off += 4;
  const strokes: InkStroke[] = [];
  const ids: Array<string | null> = [];
  for (let s = 0; s < strokeCount; s++) {
    let id: string | null = null;
    if (hasIds) {
      need(16);
      id = uuidFromBytes(body.subarray(off, off + 16));
      off += 16;
    }
    need(12);
    const n = body.readInt32BE(off);
    const t0 = Number(body.readBigInt64BE(off + 4));
    off += 12;
    if (n < 0) {
      throw new InkFormatError("negative point count");
    }
    need(n * POINT_BYTES);
    const points: InkPoint[] = new Array(n);
    for (let i = 0; i < n; i++) {
      points[i] = {
        x: body.readFloatBE(off),
        y: body.readFloatBE(off + 4),
        pressure: body.readFloatBE(off + 8),
        size: body.readFloatBE(off + 12),
        tiltX: body.readInt16BE(off + 16),
        tiltY: body.readInt16BE(off + 18),
        t: t0 + body.readInt32BE(off + 20),
      };
      off += POINT_BYTES;
    }
    if (n > 0) {
      strokes.push(points);
      ids.push(id);
    }
  }
  return { version, ids, strokes };
}

/** Writes version 2 unless asked for 1. Version 2 needs a UUID per stroke (`ids`, parallel to `strokes`). */
export function encodeInk(
  strokes: InkStroke[],
  options: { version?: InkVersion; ids?: string[] } = {},
): Buffer {
  const version = options.version ?? INK_VERSION;
  const idBytes = version >= 2 ? 16 : 0;
  if (idBytes && options.ids?.length !== strokes.length) {
    throw new InkFormatError("version 2 needs one stroke id per stroke");
  }
  const pointCount = strokes.reduce((sum, s) => sum + s.length, 0);
  const body = Buffer.alloc(4 + strokes.length * (12 + idBytes) + pointCount * POINT_BYTES);
  let off = body.writeInt32BE(strokes.length, 0);
  for (const [i, stroke] of strokes.entries()) {
    if (idBytes) {
      off += uuidToBytes(options.ids![i]).copy(body, off);
    }
    const t0 = stroke.length > 0 ? stroke[0].t : 0;
    off = body.writeInt32BE(stroke.length, off);
    off = body.writeBigInt64BE(BigInt(Math.trunc(t0)), off);
    for (const p of stroke) {
      off = body.writeFloatBE(p.x, off);
      off = body.writeFloatBE(p.y, off);
      off = body.writeFloatBE(p.pressure, off);
      off = body.writeFloatBE(p.size, off);
      off = body.writeInt16BE(p.tiltX, off);
      off = body.writeInt16BE(p.tiltY, off);
      off = body.writeInt32BE(Math.trunc(p.t - t0), off);
    }
  }
  return Buffer.concat([Buffer.from("ZDI", "latin1"), Buffer.from([version]), deflateSync(body)]);
}

// Width model from InkRenderer.java: 0.50 mm base at 300 PPI, pressure scaling when
// pressure actually varies, and a thin-thick-thin taper at both ends.
export const BASE_WIDTH_PX = 5.91;
const TAPER_FRACTION = 0.14;
const TAPER_MIN = 0.32;
const PRESSURE_MIN_SCALE = 0.55;
const PRESSURE_MAX_SCALE = 1.28;
const PRESSURE_VARIATION_RATIO = 0.06;
/** Onyx digitizers report raw pressure up to 4095 when it is not normalized. */
const DEVICE_MAX_PRESSURE = 4095;

function pressureVaries(points: InkStroke): boolean {
  let min = Infinity;
  let max = 0;
  for (const p of points) {
    min = Math.min(min, p.pressure);
    max = Math.max(max, p.pressure);
  }
  if (max <= 0) {
    return false;
  }
  return max - min > Math.max(0.02, max * PRESSURE_VARIATION_RATIO);
}

function taperEnvelope(points: InkStroke): number[] {
  const n = points.length;
  if (n === 1) {
    return [TAPER_MIN];
  }
  const dist = new Array<number>(n).fill(0);
  for (let i = 1; i < n; i++) {
    dist[i] = dist[i - 1] + Math.hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y);
  }
  const length = dist[n - 1];
  if (length < 1) {
    return new Array<number>(n).fill(TAPER_MIN + (1 - TAPER_MIN) * 0.5);
  }
  const tip = Math.max(8, length * TAPER_FRACTION);
  return dist.map((d) => {
    const edge = Math.min(d, length - d);
    let t = 1;
    if (edge < tip) {
      t = edge / tip;
      t = t * t * (3 - 2 * t);
    }
    return TAPER_MIN + (1 - TAPER_MIN) * t;
  });
}

export function strokeWidths(points: InkStroke): number[] {
  if (points.length === 0) {
    return [];
  }
  const taper = taperEnvelope(points);
  if (!pressureVaries(points)) {
    return taper.map((t) => BASE_WIDTH_PX * t);
  }
  const observedMax = Math.max(...points.map((p) => p.pressure));
  const cap = observedMax <= 1.05 ? 1 : Math.max(DEVICE_MAX_PRESSURE, observedMax);
  return points.map((p, i) => {
    const p01 = Math.min(1, Math.max(0, p.pressure / cap));
    return BASE_WIDTH_PX * (PRESSURE_MIN_SCALE + (PRESSURE_MAX_SCALE - PRESSURE_MIN_SCALE) * p01) * taper[i];
  });
}
