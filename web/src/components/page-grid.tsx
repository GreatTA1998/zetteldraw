"use client";

import { memo } from "react";
import { Skeleton } from "@/components/ui/skeleton";
import { pageLinkLabel, type Page } from "@/lib/api";
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

interface CardProps {
  page: Page;
  index: number;
  total: number;
  compact: boolean;
  onOpen: (index: number) => void;
}

/**
 * Text-only list cell — ink preview lives in the page viewer (Ready .zdl canvas),
 * not a parallel thumb image SoT.
 */
export const PageCard = memo(function PageCard({ page, index, total, compact, onOpen }: CardProps) {
  const links = page.links ?? [];
  return (
    <button
      type="button"
      onClick={() => onOpen(index)}
      className={cn(
        "group relative flex w-full flex-col items-start justify-end overflow-hidden rounded-sm border border-neutral-200/80 bg-background text-left transition hover:border-neutral-400 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-foreground",
        compact ? "min-h-10 px-1.5 py-1" : "min-h-14 px-2 py-1.5",
      )}
      style={compact ? undefined : { aspectRatio: PAGE_ASPECT }}
      aria-label={`Open page ${index + 1} of ${total}`}
    >
      <span
        className={cn(
          "tabular-nums text-neutral-600",
          compact ? "text-[0.65rem]" : "text-sm font-medium text-neutral-800",
        )}
      >
        {index + 1}
      </span>
      {!compact && links.length > 0 && (
        <span className="mt-auto max-w-full truncate text-left text-[0.65rem] text-neutral-500">
          {pageLinkLabel(links[0])}
          {links.length > 1 ? ` · +${links.length - 1}` : ""}
        </span>
      )}
    </button>
  );
});
