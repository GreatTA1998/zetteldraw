import { deflateSync, inflateSync } from "node:zlib";

/**
 * The app's `.zdi` stroke file (InkCodec.java):
 *
 *   "ZDI" u8 version
 *   zlib {
 *     i32 strokeCount
 *     per stroke: i32 pointCount, i64 firstTimestamp
 *       per point: f32 x, f32 y, f32 pressure, f32 size, i16 tiltX, i16 tiltY, i32 dtMillis
 *   }
 *
 * All integers and floats are big-endian (Java DataOutputStream). Coordinates are
 * device pixels on a page the size of the device screen.
 */

export const INK_VERSION = 1;
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

export class InkFormatError extends Error {}

export function decodeInk(data: Buffer): InkStroke[] {
  if (data.length < 4 || data.toString("latin1", 0, 3) !== "ZDI") {
    throw new InkFormatError("not a zetteldraw ink file");
  }
  if (data[3] !== INK_VERSION) {
    throw new InkFormatError(`unsupported ink version ${data[3]}`);
  }
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
  for (let s = 0; s < strokeCount; s++) {
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
    }
  }
  return strokes;
}

export function encodeInk(strokes: InkStroke[]): Buffer {
  const pointCount = strokes.reduce((sum, s) => sum + s.length, 0);
  const body = Buffer.alloc(4 + strokes.length * 12 + pointCount * POINT_BYTES);
  let off = body.writeInt32BE(strokes.length, 0);
  for (const stroke of strokes) {
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
  return Buffer.concat([Buffer.from("ZDI", "latin1"), Buffer.from([INK_VERSION]), deflateSync(body)]);
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
