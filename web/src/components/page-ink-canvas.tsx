"use client";

import { useEffect, useRef, useState } from "react";
import {
  indexOfSliceId,
  PAGE_WIDTH_PX,
  replayZdl,
  strokesForSlice,
  type PaperStroke,
  type ZdlPaper,
  ZdlFormatError,
} from "@/lib/zdl-paper";
import { PAGE_ASPECT } from "@/components/page-grid";

const paperCache = new Map<string, Promise<ZdlPaper>>();

async function loadPaper(inkUrl: string): Promise<ZdlPaper> {
  let job = paperCache.get(inkUrl);
  if (!job) {
    job = (async () => {
      const res = await fetch(inkUrl, { cache: "no-store" });
      if (!res.ok) {
        throw new Error(res.status === 404 ? "Ink log missing" : `Ink fetch failed (${res.status})`);
      }
      return replayZdl(await res.arrayBuffer());
    })().catch((err) => {
      paperCache.delete(inkUrl);
      throw err;
    });
    paperCache.set(inkUrl, job);
  }
  return job;
}

function drawStrokes(ctx: CanvasRenderingContext2D, strokes: PaperStroke[], scale: number) {
  ctx.lineCap = "round";
  ctx.lineJoin = "round";
  ctx.strokeStyle = "#111111";
  ctx.fillStyle = "#111111";
  const minWidth = 1.1 / scale;
  for (const stroke of strokes) {
    const widths = stroke.widths.map((w) => Math.max(w, minWidth));
    if (stroke.points.length === 1) {
      ctx.beginPath();
      ctx.arc(stroke.points[0].x, stroke.points[0].y, widths[0] / 2, 0, Math.PI * 2);
      ctx.fill();
      continue;
    }
    for (let i = 1; i < stroke.points.length; i++) {
      ctx.lineWidth = (widths[i - 1] + widths[i]) / 2;
      ctx.beginPath();
      ctx.moveTo(stroke.points[i - 1].x, stroke.points[i - 1].y);
      ctx.lineTo(stroke.points[i].x, stroke.points[i].y);
      ctx.stroke();
    }
  }
}

interface Props {
  inkUrl: string | null;
  pageId: string;
  pageHeight?: number;
  className?: string;
  /** When true, draw at lower res (neighbor peeks). */
  compact?: boolean;
}

export function PageInkCanvas({ inkUrl, pageId, pageHeight = 1680, className, compact }: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [status, setStatus] = useState<"loading" | "ready" | "blank" | "error">("loading");
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    if (!inkUrl) {
      setStatus("blank");
      setMessage(null);
      return;
    }
    let cancelled = false;
    setStatus("loading");
    setMessage(null);
    loadPaper(inkUrl)
      .then((paper) => {
        if (cancelled) return;
        const idx = indexOfSliceId(paper, pageId);
        if (idx < 0) {
          setStatus("blank");
          return;
        }
        const height = paper.slices[idx].height;
        const strokes = strokesForSlice(paper, idx);
        const canvas = canvasRef.current;
        if (!canvas) return;
        const outWidth = compact ? 480 : PAGE_WIDTH_PX;
        const scale = outWidth / PAGE_WIDTH_PX;
        const outHeight = Math.round(height * scale);
        canvas.width = outWidth;
        canvas.height = outHeight;
        const ctx = canvas.getContext("2d");
        if (!ctx) {
          setStatus("error");
          setMessage("Canvas unavailable");
          return;
        }
        ctx.fillStyle = "#ffffff";
        ctx.fillRect(0, 0, outWidth, outHeight);
        ctx.save();
        ctx.scale(scale, scale);
        drawStrokes(ctx, strokes, scale);
        ctx.restore();
        setStatus(strokes.length === 0 ? "blank" : "ready");
      })
      .catch((err) => {
        if (cancelled) return;
        setStatus("error");
        setMessage(err instanceof ZdlFormatError ? "Unreadable ink log" : err instanceof Error ? err.message : "Ink failed");
      });
    return () => {
      cancelled = true;
    };
  }, [inkUrl, pageId, pageHeight, compact]);

  return (
    <div className={className ?? "relative size-full overflow-hidden bg-white"} style={{ aspectRatio: PAGE_ASPECT }}>
      <canvas
        ref={canvasRef}
        aria-hidden={status !== "ready"}
        className="absolute inset-0 size-full object-contain"
        style={{ opacity: status === "ready" || status === "blank" ? 1 : 0 }}
      />
      {status === "loading" && (
        <div className="absolute inset-0 flex items-center justify-center text-sm text-neutral-400">Loading ink…</div>
      )}
      {status === "blank" && (
        <div className="absolute inset-0 flex items-center justify-center text-sm text-neutral-400">Blank page</div>
      )}
      {status === "error" && (
        <div className="absolute inset-0 flex items-center justify-center p-4 text-center text-sm text-neutral-500">
          {message ?? "This page's ink could not be rendered."}
        </div>
      )}
    </div>
  );
}
