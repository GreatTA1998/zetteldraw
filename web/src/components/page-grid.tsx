"use client";

import { memo, useState } from "react";
import { Skeleton } from "@/components/ui/skeleton";
import { pageLinkLabel, proxied, type Page } from "@/lib/api";
import { cn } from "@/lib/utils";

export const PAGE_ASPECT = "1264 / 1680";

/** Gaps ~50% of the prior chrome (was 0.5 / 0.75 / 1.25rem). */
export function gridStyle(columns: number): React.CSSProperties {
  return {
    gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))`,
    gap: columns >= 12 ? "0.25rem" : columns >= 7 ? "0.375rem" : "0.625rem",
  };
}

export function PageGridSkeleton({ columns }: { columns: number }) {
  return (
    <div className="grid" style={gridStyle(columns)} aria-busy="true" aria-label="Loading pages">
      {Array.from({ length: columns * 2 }, (_, i) => (
        <Skeleton key={i} className="w-full rounded-sm" style={{ aspectRatio: PAGE_ASPECT }} />
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
  total: number;
  compact: boolean;
  onOpen: (index: number) => void;
}

export const PageCard = memo(function PageCard({ page, index, total, compact, onOpen }: CardProps) {
  const links = page.links ?? [];
  return (
    <button
      type="button"
      onClick={() => onOpen(index)}
      className="group relative w-full overflow-hidden rounded-sm border border-neutral-200/80 bg-background text-left transition hover:border-neutral-400 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-foreground"
      aria-label={`Open page ${index + 1} of ${total}`}
    >
      <PageImage page={page} />
      {/* Android-like page chrome: gray k/n, no charcoal badge */}
      {!compact && (
        <span className="pointer-events-none absolute top-1 left-1.5 text-[0.65rem] tabular-nums text-neutral-500/90">
          {index + 1}/{total}
        </span>
      )}
      {!compact && links.length > 0 && (
        <span className="pointer-events-none absolute bottom-1 left-1.5 max-w-[90%] truncate text-left text-[0.6rem] text-neutral-600">
          {pageLinkLabel(links[0])}
          {links.length > 1 ? ` · +${links.length - 1}` : ""}
        </span>
      )}
    </button>
  );
});
