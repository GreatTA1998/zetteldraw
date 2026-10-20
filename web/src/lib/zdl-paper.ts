/**
 * Ready notebook ink log (.zdl) — full stroke + structure replay for the web companion.
 * Matches {@code NotebookPaper.replay} (APPEND/DELETE/TRANSLATE/TEAR/EXTEND).
 * Server keeps structure-only {@code readSliceIndex}; this client module is the ink SoT reader.
 */

const MAGIC0 = 0x5a; // Z
const MAGIC1 = 0x44; // D
const MAGIC2 = 0x4c; // L
const VERSION = 1;
const OP_APPEND = 1;
const OP_DELETE = 2;
const OP_TRANSLATE = 3;
const OP_TEAR = 4;
const OP_EXTEND = 5;
const TEAR_WIPE = 2;
const STROKE_POINT_BYTES = 28;
const BASE_WIDTH_PX = 5.91;

export class ZdlFormatError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ZdlFormatError";
  }
}

export interface PaperPoint {
  x: number;
  y: number;
  pressure: number;
  size: number;
  tiltX: number;
  tiltY: number;
  t: number;
}

export interface PaperStroke {
  id: string;
  points: PaperPoint[];
  widths: number[];
  color: number;
  bounds: { left: number; top: number; right: number; bottom: number };
  maxWidth: number;
}

export interface ZdlSlice {
  id: string;
  height: number;
}

export interface ZdlPaper {
  sliceHeight: number;
  slices: ZdlSlice[];
  strokes: PaperStroke[];
}

function boundsOf(points: PaperPoint[], pad: number) {
  if (points.length === 0) {
    return { left: 0, top: 0, right: 0, bottom: 0 };
  }
  let left = points[0].x;
  let top = points[0].y;
  let right = points[0].x;
  let bottom = points[0].y;
  for (let i = 1; i < points.length; i++) {
    const p = points[i];
    if (p.x < left) left = p.x;
    if (p.y < top) top = p.y;
    if (p.x > right) right = p.x;
    if (p.y > bottom) bottom = p.y;
  }
  return { left: left - pad, top: top - pad, right: right + pad, bottom: bottom + pad };
}

function makeStroke(id: string, points: PaperPoint[], widths: number[], color = 0xff000000): PaperStroke {
  let max = BASE_WIDTH_PX;
  for (const w of widths) {
    if (w > max) max = w;
  }
  return { id, points, widths, color, maxWidth: max, bounds: boundsOf(points, max * 0.5 + 2) };
}

function translateStroke(stroke: PaperStroke, dx: number, dy: number): PaperStroke {
  if (dx === 0 && dy === 0) return stroke;
  const points = stroke.points.map((p) => ({ ...p, x: p.x + dx, y: p.y + dy }));
  return makeStroke(stroke.id, points, stroke.widths.slice(), stroke.color);
}

function readId(view: DataView, offset: number): { id: string; next: number } {
  if (offset + 2 > view.byteLength) throw new ZdlFormatError("truncated id");
  const n = view.getUint16(offset);
  const start = offset + 2;
  if (n > view.byteLength - start) throw new ZdlFormatError("truncated id body");
  const bytes = new Uint8Array(view.buffer, view.byteOffset + start, n);
  return { id: new TextDecoder().decode(bytes), next: start + n };
}

function readIds(view: DataView, offset: number): { ids: string[]; next: number } {
  if (offset + 4 > view.byteLength) throw new ZdlFormatError("truncated id list");
  const n = view.getInt32(offset);
  if (n < 0 || n > 100_000) throw new ZdlFormatError(`bad id count ${n}`);
  let o = offset + 4;
  const ids: string[] = [];
  for (let i = 0; i < n; i++) {
    const id = readId(view, o);
    ids.push(id.id);
    o = id.next;
  }
  return { ids, next: o };
}

function readStroke(view: DataView, offset: number): { stroke: PaperStroke; next: number } {
  const id = readId(view, offset);
  let o = id.next;
  if (o + 12 > view.byteLength) throw new ZdlFormatError("truncated stroke");
  const n = view.getInt32(o);
  o += 4;
  if (n < 0 || n > (view.byteLength - (o + 8)) / STROKE_POINT_BYTES) {
    throw new ZdlFormatError(`bad stroke point count ${n}`);
  }
  const t0 = Number(view.getBigInt64(o));
  o += 8;
  const points: PaperPoint[] = new Array(n);
  const widths = new Array<number>(n);
  for (let i = 0; i < n; i++) {
    points[i] = {
      x: view.getFloat32(o),
      y: view.getFloat32(o + 4),
      pressure: view.getFloat32(o + 8),
      size: view.getFloat32(o + 12),
      tiltX: view.getInt16(o + 16),
      tiltY: view.getInt16(o + 18),
      t: t0 + view.getInt32(o + 20),
    };
    widths[i] = view.getFloat32(o + 24);
    o += STROKE_POINT_BYTES;
  }
  if (o + 4 <= view.byteLength) {
    o += 4; // legacy ARGB trailer
  }
  return { stroke: makeStroke(id.id, points, widths), next: o };
}

interface Piece {
  points: PaperPoint[];
  widths: number[];
  inside: boolean;
}

function sideOf(y: number, top: number, bottom: number): number {
  if (y < top) return -1;
  if (y >= bottom) return 1;
  return 0;
}

function edgesBetween(from: number, to: number, top: number, bottom: number): number[] {
  if (from === to) return [];
  if (from < 0 && to > 0) return [top, bottom];
  if (from > 0 && to < 0) return [bottom, top];
  if ((from < 0 && to === 0) || (from === 0 && to < 0)) return [top];
  return [bottom];
}

function lerp(
  a: PaperPoint,
  b: PaperPoint,
  wa: number,
  wb: number,
  t: number,
): { point: PaperPoint; width: number } {
  if (t < 0) t = 0;
  else if (t > 1) t = 1;
  return {
    point: {
      x: a.x + (b.x - a.x) * t,
      y: a.y + (b.y - a.y) * t,
      pressure: a.pressure + (b.pressure - a.pressure) * t,
      size: a.size + (b.size - a.size) * t,
      tiltX: Math.round(a.tiltX + (b.tiltX - a.tiltX) * t),
      tiltY: Math.round(a.tiltY + (b.tiltY - a.tiltY) * t),
      t: a.t + (b.t - a.t) * t,
    },
    width: wa + (wb - wa) * t,
  };
}

function split(stroke: PaperStroke, top: number, bottom: number): Piece[] {
  const points = stroke.points;
  const widths = stroke.widths;
  const pieces: Piece[] = [];
  let cur: PaperPoint[] = [{ ...points[0] }];
  let curW: number[] = [widths[0]];
  let side = sideOf(points[0].y, top, bottom);
  for (let i = 1; i < points.length; i++) {
    const point = points[i];
    const next = sideOf(point.y, top, bottom);
    if (next === side) {
      cur.push({ ...point });
      curW.push(widths[i]);
      continue;
    }
    const edges = edgesBetween(side, next, top, bottom);
    const prev = points[i - 1];
    const prevW = widths[i - 1];
    let walk = side;
    for (const edge of edges) {
      const span = point.y - prev.y;
      const t = span === 0 ? 0 : (edge - prev.y) / span;
      const at = lerp(prev, point, prevW, widths[i], t);
      cur.push(at.point);
      curW.push(at.width);
      pieces.push({ points: cur, widths: curW, inside: walk === 0 });
      cur = [{ ...at.point }];
      curW = [at.width];
      walk = walk + (next > side ? 1 : -1);
    }
    cur.push({ ...point });
    curW.push(widths[i]);
    side = next;
  }
  if (cur.length > 0) {
    pieces.push({ points: cur, widths: curW, inside: side === 0 });
  }
  return pieces;
}

function whollyInside(stroke: PaperStroke, top: number, bottom: number): boolean {
  for (const p of stroke.points) {
    if (p.y < top || p.y >= bottom) return false;
  }
  return true;
}

function originOf(heights: number[], index: number): number {
  let y = 0;
  const n = Math.min(Math.max(index, 0), heights.length);
  for (let i = 0; i < n; i++) y += heights[i];
  return y;
}

/** Full Ready replay — strokes + structure. */
export function replayZdl(bytes: ArrayBuffer): ZdlPaper {
  const view = new DataView(bytes);
  if (
    view.byteLength < 12 ||
    view.getUint8(0) !== MAGIC0 ||
    view.getUint8(1) !== MAGIC1 ||
    view.getUint8(2) !== MAGIC2
  ) {
    throw new ZdlFormatError("not a notebook ink log");
  }
  if (view.getUint8(3) !== VERSION) {
    throw new ZdlFormatError(`unsupported notebook log ${view.getUint8(3)}`);
  }
  let o = 4;
  const sliceHeight = view.getInt32(o);
  o += 4;
  const sliceCount = view.getInt32(o);
  o += 4;
  if (sliceCount < 0 || sliceCount > 100_000) {
    throw new ZdlFormatError(`bad slice count ${sliceCount}`);
  }
  const heights: number[] = [];
  const sliceIds: string[] = [];
  for (let i = 0; i < sliceCount; i++) {
    if (o + 4 > view.byteLength) throw new ZdlFormatError("truncated slice height");
    const height = view.getInt32(o);
    o += 4;
    if (height <= 0) throw new ZdlFormatError("bad slice height");
    const id = readId(view, o);
    o = id.next;
    heights.push(height);
    sliceIds.push(id.id);
  }

  let strokes: PaperStroke[] = [];

  const shiftStrokesFrom = (y: number, dy: number) => {
    for (let i = 0; i < strokes.length; i++) {
      const stroke = strokes[i];
      for (const p of stroke.points) {
        if (p.y >= y) {
          strokes[i] = translateStroke(stroke, 0, dy);
          break;
        }
      }
    }
  };

  const closeGap = (slice: number) => {
    const height = heights[slice];
    shiftStrokesFrom(originOf(heights, slice) + height - 0.01, -height);
    heights.splice(slice, 1);
    sliceIds.splice(slice, 1);
  };

  const cut = (slice: number, recordedNewIds: string[]) => {
    const top = originOf(heights, slice);
    const bottom = top + heights[slice];
    let cursor = 0;
    const next: PaperStroke[] = [];
    for (const stroke of strokes) {
      if (stroke.bounds.bottom < top || stroke.bounds.top >= bottom) {
        next.push(stroke);
        continue;
      }
      if (whollyInside(stroke, top, bottom)) {
        continue;
      }
      const pieces = split(stroke, top, bottom);
      let keptId = false;
      for (const piece of pieces) {
        let id: string;
        if (!piece.inside && !keptId) {
          id = stroke.id;
          keptId = true;
        } else {
          id = cursor < recordedNewIds.length ? recordedNewIds[cursor++] : stroke.id;
        }
        if (!piece.inside) {
          next.push(makeStroke(id, piece.points, piece.widths, stroke.color));
        }
      }
    }
    strokes = next;
  };

  const applyTear = (slice: number, kind: number, newIds: string[]) => {
    cut(slice, newIds);
    if (kind !== TEAR_WIPE) closeGap(slice);
  };

  while (o + 5 <= view.byteLength) {
    const op = view.getUint8(o);
    o += 1;
    const len = view.getInt32(o);
    o += 4;
    if (len < 0 || o + len > view.byteLength) break;
    const start = o;
    const end = o + len;
    o = end;
    try {
      const payload = new DataView(bytes, start, len);
      let p = 0;
      if (op === OP_APPEND) {
        const { stroke } = readStroke(payload, 0);
        strokes.push(stroke);
      } else if (op === OP_DELETE) {
        const { ids } = readIds(payload, 0);
        const gone = new Set(ids);
        strokes = strokes.filter((s) => !gone.has(s.id));
      } else if (op === OP_TRANSLATE) {
        const ids = readIds(payload, 0);
        p = ids.next;
        const dx = payload.getFloat32(p);
        const dy = payload.getFloat32(p + 4);
        const want = new Set(ids.ids);
        strokes = strokes.map((s) => (want.has(s.id) ? translateStroke(s, dx, dy) : s));
      } else if (op === OP_TEAR) {
        if (len < 5) throw new ZdlFormatError("truncated tear");
        const kind = payload.getUint8(0);
        const slice = payload.getInt32(1);
        const newIds = readIds(payload, 5).ids;
        if (slice >= 0 && slice < heights.length) {
          applyTear(slice, kind, newIds);
        }
      } else if (op === OP_EXTEND) {
        const id = readId(payload, 0);
        p = id.next;
        if (p + 4 > len) throw new ZdlFormatError("truncated extend");
        const height = payload.getInt32(p);
        if (height <= 0) throw new ZdlFormatError("bad extend height");
        heights.push(height);
        sliceIds.push(id.id);
      } else {
        throw new ZdlFormatError(`bad log op ${op}`);
      }
    } catch {
      // Heal-in-place: keep good prefix (same as NotebookPaper.replay).
      break;
    }
  }

  return {
    sliceHeight,
    slices: sliceIds.map((id, i) => ({ id, height: heights[i] })),
    strokes,
  };
}

/** Go 7 portrait page width used for companion preview scaling. */
export const PAGE_WIDTH_PX = 1264;

/** Strokes whose bounds meet this slice, shifted into page-local coordinates. */
export function strokesForSlice(paper: ZdlPaper, sliceIndex: number): PaperStroke[] {
  if (sliceIndex < 0 || sliceIndex >= paper.slices.length) return [];
  const top = originOf(
    paper.slices.map((s) => s.height),
    sliceIndex,
  );
  const bottom = top + paper.slices[sliceIndex].height;
  const hit: PaperStroke[] = [];
  for (const stroke of paper.strokes) {
    if (stroke.bounds.bottom >= top && stroke.bounds.top < bottom) {
      hit.push(translateStroke(stroke, 0, -top));
    }
  }
  return hit;
}

export function indexOfSliceId(paper: ZdlPaper, id: string): number {
  return paper.slices.findIndex((s) => s.id === id);
}
