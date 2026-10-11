import { createCanvas } from "@napi-rs/canvas";
import { decodeInkFile, strokeWidths, type InkStroke } from "./ink.js";

export interface PageSize {
  width: number;
  height: number;
}

/**
 * The file does not record the page size (a page is the device screen). The default
 * is the Boox Go 7 in portrait; ink outside it grows the box at the same aspect so
 * a page from a larger device is scaled down rather than cropped.
 */
export const DEFAULT_PAGE: PageSize = { width: 1264, height: 1680 };
const OVERFLOW_PAD = 24;

export function pageBox(strokes: InkStroke[], page: PageSize = DEFAULT_PAGE): PageSize {
  let maxX = 0;
  let maxY = 0;
  for (const stroke of strokes) {
    for (const p of stroke) {
      if (p.x > maxX) maxX = p.x;
      if (p.y > maxY) maxY = p.y;
    }
  }
  const scale = Math.max(1, (maxX + OVERFLOW_PAD) / page.width, (maxY + OVERFLOW_PAD) / page.height);
  return { width: Math.ceil(page.width * scale), height: Math.ceil(page.height * scale) };
}

/** Draws like InkRenderer.draw on the device: round-capped segments, width averaged per segment. */
export function renderPng(strokes: InkStroke[], outWidth: number, page: PageSize = DEFAULT_PAGE): Buffer {
  return renderPngColored(
    strokes,
    strokes.map(() => 0xff111111),
    outWidth,
    page,
  );
}

/** Same as {@link renderPng} but honors per-stroke ARGB colors from ZDI v3. */
export function renderPngColored(
  strokes: InkStroke[],
  colors: number[],
  outWidth: number,
  page: PageSize = DEFAULT_PAGE,
): Buffer {
  const box = pageBox(strokes, page);
  const scale = outWidth / box.width;
  const outHeight = Math.round(box.height * scale);
  const canvas = createCanvas(outWidth, outHeight);
  const ctx = canvas.getContext("2d");
  ctx.fillStyle = "#ffffff";
  ctx.fillRect(0, 0, outWidth, outHeight);
  ctx.scale(scale, scale);
  ctx.lineCap = "round";
  ctx.lineJoin = "round";
  // Keep hairlines visible when a thumbnail shrinks the page several times over.
  const minWidth = 1.1 / scale;
  for (let s = 0; s < strokes.length; s++) {
    const stroke = strokes[s];
    const css = argbToCss(colors[s] ?? 0xff111111);
    ctx.strokeStyle = css;
    ctx.fillStyle = css;
    const widths = strokeWidths(stroke).map((w) => Math.max(w, minWidth));
    if (stroke.length === 1) {
      ctx.beginPath();
      ctx.arc(stroke[0].x, stroke[0].y, widths[0] / 2, 0, Math.PI * 2);
      ctx.fill();
      continue;
    }
    for (let i = 1; i < stroke.length; i++) {
      ctx.lineWidth = (widths[i - 1] + widths[i]) / 2;
      ctx.beginPath();
      ctx.moveTo(stroke[i - 1].x, stroke[i - 1].y);
      ctx.lineTo(stroke[i].x, stroke[i].y);
      ctx.stroke();
    }
  }
  return canvas.toBuffer("image/png");
}

function argbToCss(argb: number): string {
  const a = ((argb >>> 24) & 0xff) / 255;
  const r = (argb >>> 16) & 0xff;
  const g = (argb >>> 8) & 0xff;
  const b = argb & 0xff;
  if (a >= 1) {
    return `rgb(${r},${g},${b})`;
  }
  return `rgba(${r},${g},${b},${a})`;
}

/** Decode a ZDI blob and render with its colors when present. */
export function renderInkBytes(data: Buffer, outWidth: number, page: PageSize = DEFAULT_PAGE): Buffer {
  const file = decodeInkFile(data);
  return renderPngColored(file.strokes, file.colors, outWidth, page);
}
