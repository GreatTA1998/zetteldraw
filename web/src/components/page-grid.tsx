"use client";

import { useSortable } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { FolderInput, MoreHorizontal } from "lucide-react";
import { memo } from "react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Skeleton } from "@/components/ui/skeleton";
import { proxied, type Notebook, type Page } from "@/lib/api";
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
  return (
    <div className={cn("relative w-full overflow-hidden bg-white", className)} style={{ aspectRatio: PAGE_ASPECT }}>
      {src ? (
        // eslint-disable-next-line @next/next/no-img-element -- auth-proxied PNGs; next/image would refetch them without the cookie
        <img
          src={src}
          alt=""
          loading="lazy"
          decoding="async"
          draggable={false}
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
  moveTargets: Notebook[];
  onOpen: (index: number) => void;
  onMove: (page: Page, notebook: Notebook) => void;
}

export const SortablePageCard = memo(function SortablePageCard({
  page,
  index,
  compact,
  moveTargets,
  onOpen,
  onMove,
}: CardProps) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: page.id });
  const { onKeyDown: dndKeyDown, ...pointerListeners } = listeners ?? {};

  return (
    <div
      ref={setNodeRef}
      style={{ transform: CSS.Translate.toString(transform), transition }}
      className={cn("group relative touch-none", isDragging && "z-10 opacity-30")}
    >
      <div
        {...attributes}
        {...pointerListeners}
        role="button"
        aria-roledescription="page"
        aria-label={`Page ${index + 1}. Enter to open, Space to pick up and reorder.`}
        onClick={() => onOpen(index)}
        onKeyDown={(e) => {
          if (e.key === "Enter") {
            e.preventDefault();
            onOpen(index);
            return;
          }
          dndKeyDown?.(e);
        }}
        className="cursor-pointer rounded-md shadow-[0_1px_2px_rgb(0_0_0/0.08)] ring-1 ring-black/10 outline-none transition-shadow hover:shadow-md hover:ring-black/25 focus-visible:ring-2 focus-visible:ring-ring"
      >
        <PageImage page={page} className="rounded-md" />
      </div>
      <span
        className={cn(
          "pointer-events-none absolute bottom-1 left-1 rounded bg-white/85 px-1 font-medium tabular-nums text-neutral-500 ring-1 ring-black/5",
          compact ? "text-[0.6rem]" : "text-[0.7rem]",
        )}
      >
        {index + 1}
      </span>
      {moveTargets.length > 0 && (
        <DropdownMenu>
          <DropdownMenuTrigger
            render={
              <Button
                variant="secondary"
                size="icon-xs"
                aria-label={`Move page ${index + 1}`}
                className="absolute top-1 right-1 bg-white/90 opacity-0 shadow-sm ring-1 ring-black/10 group-hover:opacity-100 focus-visible:opacity-100 aria-expanded:opacity-100"
                onPointerDown={(e) => e.stopPropagation()}
              />
            }
          >
            <MoreHorizontal />
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="max-h-80 min-w-48">
            <DropdownMenuGroup>
              <DropdownMenuLabel>Move to</DropdownMenuLabel>
              {moveTargets.map((nb) => (
                <DropdownMenuItem key={nb.id} onClick={() => onMove(page, nb)}>
                  <FolderInput />
                  <span className="truncate">{nb.title}</span>
                </DropdownMenuItem>
              ))}
            </DropdownMenuGroup>
          </DropdownMenuContent>
        </DropdownMenu>
      )}
    </div>
  );
});
