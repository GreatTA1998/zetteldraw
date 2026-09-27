import { createCanvas } from "@napi-rs/canvas";
import { strokeWidths, type InkStroke } from "./ink.js";

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
  const box = pageBox(strokes, page);
  const scale = outWidth / box.width;
  const outHeight = Math.round(box.height * scale);
  const canvas = createCanvas(outWidth, outHeight);
  const ctx = canvas.getContext("2d");
  ctx.fillStyle = "#ffffff";
  ctx.fillRect(0, 0, outWidth, outHeight);
  ctx.scale(scale, scale);
  ctx.strokeStyle = "#111111";
  ctx.fillStyle = "#111111";
  ctx.lineCap = "round";
  ctx.lineJoin = "round";
  // Keep hairlines visible when a thumbnail shrinks the page several times over.
  const minWidth = 1.1 / scale;
  for (const stroke of strokes) {
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
