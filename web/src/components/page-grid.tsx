"use client";

import { memo, useState } from "react";
import { Skeleton } from "@/components/ui/skeleton";
import { proxied, type Page } from "@/lib/api";
import { cn } from "@/lib/utils";

export const PAGE_ASPECT = "1264 / 1680";

export function gridStyle(columns: number): React.CSSProperties {
  return {
    gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))`,
    gap: columns >= 12 ? "0.5rem" : columns >= 7 ? "0.75rem" : "1.25rem",
  };
}

export function PageGridSkeleton({ columns }: { columns: number }) {
  return (
    <div className="grid" style={gridStyle(columns)} aria-busy="true" aria-label="Loading pages">
      {Array.from({ length: columns * 2 }, (_, i) => (
        <Skeleton key={i} className="w-full rounded-md" style={{ aspectRatio: PAGE_ASPECT }} />
      ))}
    </div>
  );
}

export function PageImage({ page, className }: { page: Page; className?: string }) {
  const src = proxied(page.thumb_url);
  const [failedSrc, setFailedSrc] = useState<string | null>(null);
  return (
    <div className={cn("relative w-full overflow-hidden bg-white", className)} style={{ aspectRatio: PAGE_ASPECT }}>
      {src && failedSrc === src ? (
        <div className="absolute inset-0 flex items-center justify-center p-2 text-center text-[0.7rem] text-neutral-400">
          Preview unavailable
        </div>
      ) : src ? (
        // eslint-disable-next-line @next/next/no-img-element -- auth-proxied PNGs; next/image would refetch them without the cookie
        <img
          src={src}
          alt=""
          loading="lazy"
          decoding="async"
          draggable={false}
          onError={() => setFailedSrc(src)}
          className="absolute inset-0 size-full object-contain"
        />
      ) : (
        <div className="absolute inset-0 flex items-center justify-center text-[0.7rem] text-neutral-400">Blank</div>
      )}
    </div>
  );
}

interface CardProps {
  page: Page;
  index: number;
  compact: boolean;
  onOpen: (index: number) => void;
}

export const PageCard = memo(function PageCard({ page, index, compact, onOpen }: CardProps) {
  return (
    <button
      type="button"
      onClick={() => onOpen(index)}
      className="group relative w-full overflow-hidden rounded-md border bg-background text-left shadow-sm transition hover:ring-2 hover:ring-foreground/20 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-foreground"
      aria-label={`Open page ${index + 1}`}
    >
      <PageImage page={page} />
      {!compact && (
        <span className="absolute bottom-1 right-1 rounded bg-black/60 px-1.5 py-0.5 text-[0.65rem] text-white tabular-nums">
          {index + 1}
        </span>
      )}
    </button>
  );
});
