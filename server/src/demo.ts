import { createHash } from "node:crypto";
import { encodeInk, type InkPoint, type InkStroke } from "./ink.js";
import type { BoardRow, Incoming, NotebookRow } from "./lww.js";
import { positionBetween } from "./order.js";
import { DEFAULT_PAGE } from "./render.js";
import { sha256Hex } from "./sync.js";

/**
 * A fake library so the web overview runs without a Boox: notebooks of pages with
 * synthetic handwriting and diagrams, encoded as real `.zdi` ink files.
 */

type Kind = "notes" | "sketch" | "mixed" | "list" | "blank";

interface NotebookSpec {
  title: string | null;
  pages: Kind[];
}

const LIBRARY: NotebookSpec[] = [
  { title: null, pages: ["notes", "sketch", "list", "mixed", "notes", "sketch", "mixed", "notes", "list", "sketch"] },
  {
    title: "Reading notes",
    pages: ["notes", "notes", "mixed", "notes", "list", "notes", "mixed", "notes", "notes", "sketch", "notes", "mixed", "notes", "list", "notes", "notes", "mixed", "notes"],
  },
  {
    title: "zetteldraw design",
    pages: ["sketch", "mixed", "sketch", "list", "mixed", "sketch", "notes", "sketch", "blank", "mixed", "sketch", "list"],
  },
  { title: "Meetings", pages: ["list", "notes", "list", "mixed", "list", "notes", "list", "list", "mixed"] },
  { title: "Sketchbook", pages: ["sketch", "sketch", "sketch", "mixed", "sketch", "sketch", "sketch"] },
  { title: "Ideas", pages: ["mixed", "notes", "sketch", "list"] },
  { title: "Garden plans", pages: [] },
];

const BASE_TIME = Date.UTC(2026, 6, 1, 9, 0, 0);
const DAY = 86_400_000;

export function demoId(name: string): string {
  const h = createHash("sha256").update(`zetteldraw-demo:${name}`).digest("hex");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-4${h.slice(13, 16)}-8${h.slice(17, 20)}-${h.slice(20, 32)}`;
}

function rng(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

class Pen {
  strokes: InkStroke[] = [];
  private t = 0;
  constructor(private readonly rand: () => number) {}

  stroke(xy: Array<[number, number]>): void {
    const n = xy.length;
    const phase = this.rand() * Math.PI * 2;
    const points: InkPoint[] = xy.map(([x, y], i) => ({
      x,
      y,
      pressure: 0.55 + 0.3 * Math.sin(phase + (i / Math.max(1, n)) * Math.PI * 2),
      size: 1,
      tiltX: 0,
      tiltY: 0,
      t: (this.t += 8),
    }));
    this.t += 300;
    this.strokes.push(points);
  }

  jitter(v: number, amount: number): number {
    return v + (this.rand() - 0.5) * amount;
  }
}

/** A cursive word: a prolate cycloid, taller loops for ascenders. Returns the end x. */
function word(pen: Pen, x0: number, base: number, letters: number, h: number): number {
  const pts: Array<[number, number]> = [];
  const r = h * 0.28;
  let x = x0;
  for (let l = 0; l < letters; l++) {
    const tall = pen.jitter(0, 1) > 0.3;
    const lh = h * (tall ? 1.9 : 1) * (0.85 + pen.jitter(0, 0.3));
    const d = r * (2.1 + pen.jitter(0, 0.8));
    for (let k = 0; k <= 16; k++) {
      const t = (k / 16) * Math.PI * 2;
      const y = base - (lh * (1 - Math.cos(t))) / 2;
      pts.push([x + r * t + d * Math.sin(t) + (base - y) * 0.18, y]);
    }
    x += r * Math.PI * 2;
  }
  pen.stroke(pts);
  return x;
}

function textLine(pen: Pen, x0: number, base: number, maxX: number, h: number, words = 99): number {
  let x = x0;
  for (let w = 0; w < words; w++) {
    const letters = 2 + Math.floor(pen.jitter(4, 7));
    const width = letters * h * 0.28 * Math.PI * 2;
    if (x + width > maxX) {
      break;
    }
    x = word(pen, x, pen.jitter(base, 4), letters, h) + h * 0.9;
  }
  return x;
}

function wobblyLine(pen: Pen, x1: number, y1: number, x2: number, y2: number): Array<[number, number]> {
  const pts: Array<[number, number]> = [];
  const steps = Math.max(6, Math.round(Math.hypot(x2 - x1, y2 - y1) / 18));
  for (let i = 0; i <= steps; i++) {
    const f = i / steps;
    pts.push([pen.jitter(x1 + (x2 - x1) * f, 3), pen.jitter(y1 + (y2 - y1) * f, 3)]);
  }
  return pts;
}

function box(pen: Pen, x: number, y: number, w: number, h: number): void {
  pen.stroke([
    ...wobblyLine(pen, x, y, x + w, y),
    ...wobblyLine(pen, x + w, y, x + w, y + h),
    ...wobblyLine(pen, x + w, y + h, x, y + h),
    ...wobblyLine(pen, x, y + h, x + 4, y - 3),
  ]);
}

function ellipse(pen: Pen, cx: number, cy: number, rx: number, ry: number): void {
  const pts: Array<[number, number]> = [];
  const start = pen.jitter(0, 2);
  for (let i = 0; i <= 48; i++) {
    const a = start + (i / 44) * Math.PI * 2;
    pts.push([cx + rx * Math.cos(a) * (1 + i * 0.001), cy + ry * Math.sin(a)]);
  }
  pen.stroke(pts);
}

function arrow(pen: Pen, x1: number, y1: number, x2: number, y2: number): void {
  pen.stroke(wobblyLine(pen, x1, y1, x2, y2));
  const a = Math.atan2(y2 - y1, x2 - x1);
  const s = 26;
  pen.stroke([
    [x2 - s * Math.cos(a - 0.45), y2 - s * Math.sin(a - 0.45)],
    [x2, y2],
    [x2 - s * Math.cos(a + 0.45), y2 - s * Math.sin(a + 0.45)],
  ]);
}

function diagram(pen: Pen, top: number, bottom: number): void {
  const { width } = DEFAULT_PAGE;
  const nodes = 3 + Math.floor(pen.jitter(1.5, 3));
  const placed: Array<[number, number, number, number]> = [];
  for (let i = 0; i < nodes; i++) {
    const w = pen.jitter(260, 80);
    const h = pen.jitter(130, 40);
    const col = i % 2;
    const x = 110 + col * (width - 220 - w) + pen.jitter(0, 60);
    const y = top + (i * (bottom - top - h)) / Math.max(1, nodes - 1);
    if (pen.jitter(0, 1) > 0.25) {
      box(pen, x, y, w, h);
    } else {
      ellipse(pen, x + w / 2, y + h / 2, w / 2, h / 2);
    }
    const labelH = 20;
    const letters = Math.max(2, Math.min(6, Math.floor((w - 70) / (labelH * 0.28 * Math.PI * 2))));
    word(pen, x + 30, y + h / 2 + 12, letters, labelH);
    placed.push([x, y, w, h]);
  }
  for (let i = 1; i < placed.length; i++) {
    const [ax, ay, aw, ah] = placed[i - 1];
    const [bx, by, bw] = placed[i];
    arrow(pen, ax + aw / 2, ay + ah + 8, bx + bw / 2, by - 10);
  }
}

function pageInk(kind: Kind, seed: number): InkStroke[] {
  const pen = new Pen(rng(seed));
  const { width, height } = DEFAULT_PAGE;
  const left = 100;
  const right = width - 90;
  if (kind === "blank") {
    return [];
  }
  // Title and underline.
  const titleEnd = textLine(pen, left, 170, right - 200, 44, 3);
  pen.stroke(wobblyLine(pen, left - 10, 200, Math.min(titleEnd, right), 204));
  let y = 290;
  if (kind === "sketch") {
    diagram(pen, y, height - 260);
    textLine(pen, left, height - 150, right, 26);
    return pen.strokes;
  }
  if (kind === "mixed") {
    for (let i = 0; i < 5; i++, y += 70) textLine(pen, left, y, right, 26);
    diagram(pen, y + 20, height - 160);
    return pen.strokes;
  }
  const lines = 13 + Math.floor(pen.jitter(2, 4));
  for (let i = 0; i < lines && y < height - 120; i++, y += 82) {
    if (kind === "list") {
      const checkbox = pen.jitter(0, 1) > 0;
      if (checkbox) {
        box(pen, left, y - 34, 34, 34);
        if (pen.jitter(0, 1) > 0) {
          pen.stroke([
            [left + 6, y - 18],
            [left + 15, y - 6],
            [left + 40, y - 48],
          ]);
        }
      } else {
        ellipse(pen, left + 16, y - 16, 7, 7);
      }
      textLine(pen, left + 64, y, right, 26, 2 + Math.floor(pen.jitter(3, 4)));
    } else {
      const indent = pen.jitter(0, 1) > 0.35 ? 60 : 0;
      textLine(pen, left + indent, y, i % 5 === 4 ? right - 360 : right, 26);
    }
  }
  return pen.strokes;
}

export interface DemoLibrary {
  notebooks: Incoming<NotebookRow>[];
  boards: Incoming<BoardRow>[];
  blobs: Map<string, Buffer>;
}

export function demoLibrary(): DemoLibrary {
  const notebooks: Incoming<NotebookRow>[] = [];
  const boards: Incoming<BoardRow>[] = [];
  const blobs = new Map<string, Buffer>();
  let notebookPos: string | null = null;
  let seed = 1;
  LIBRARY.forEach((spec, n) => {
    let notebookId: string | null = null;
    if (spec.title !== null) {
      notebookId = demoId(`notebook:${spec.title}`);
      notebookPos = positionBetween(notebookPos, null);
      notebooks.push({
        id: notebookId,
        title: spec.title,
        position: notebookPos,
        created_at: BASE_TIME + n * DAY,
        updated_at: BASE_TIME + n * DAY,
        deleted_at: null,
        base_rev: 0,
      });
    }
    let pos: string | null = null;
    spec.pages.forEach((kind, i) => {
      pos = positionBetween(pos, null);
      const created = BASE_TIME + (n * 9 + i) * DAY * 0.7;
      let inkHash: string | null = null;
      let inkBytes = 0;
      const strokes = pageInk(kind, seed++);
      if (strokes.length > 0) {
        const bytes = encodeInk(strokes);
        inkHash = sha256Hex(bytes);
        inkBytes = bytes.length;
        blobs.set(inkHash, bytes);
      }
      boards.push({
        id: demoId(`page:${spec.title ?? "scratchpad"}:${i}`),
        notebook_id: notebookId,
        position: pos,
        ink_hash: inkHash,
        ink_bytes: inkBytes,
        thumb_hash: null,
        conflict_of: null,
        created_at: Math.round(created),
        updated_at: Math.round(created + 3_600_000),
        deleted_at: null,
        base_rev: 0,
      });
    });
  });
  return { notebooks, boards, blobs };
}
