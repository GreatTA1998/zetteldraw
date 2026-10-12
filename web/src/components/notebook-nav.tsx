"use client";

import { Settings } from "lucide-react";
import { useMemo, useState } from "react";
import { HandOffDialog } from "@/components/hand-off-dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Skeleton } from "@/components/ui/skeleton";
import { SCRATCHPAD, type Notebook } from "@/lib/api";
import { cn } from "@/lib/utils";

interface Props {
  notebooks: Notebook[] | undefined;
  selectedId: string | undefined;
  onSelect: (id: string) => void;
  onSignOut?: () => void;
}

function childrenOf(notebooks: Notebook[], parentId: string | null): Notebook[] {
  return notebooks.filter((n) => n.kind === "notebook" && (n.parent_id ?? null) === parentId);
}

function notebookById(notebooks: Notebook[], id: string): Notebook | undefined {
  return notebooks.find((n) => n.id === id);
}

/** Ancestor chain from top-level down to selected (excluding Scratchpad). */
function trailTo(notebooks: Notebook[], selectedId: string | undefined): string[] {
  if (!selectedId || selectedId === SCRATCHPAD) return [];
  const trail: string[] = [];
  let cursor: string | null = selectedId;
  const guard = new Set<string>();
  while (cursor) {
    if (guard.has(cursor)) break;
    guard.add(cursor);
    const nb = notebookById(notebooks, cursor);
    if (!nb) break;
    trail.unshift(nb.id);
    cursor = nb.parent_id ?? null;
  }
  return trail;
}

export function NotebookNav({ notebooks, selectedId, onSelect, onSignOut }: Props) {
  const [handOffOpen, setHandOffOpen] = useState(false);
  const trail = useMemo(
    () => (notebooks ? trailTo(notebooks, selectedId) : []),
    [notebooks, selectedId],
  );

  const topLevel = useMemo(() => {
    if (!notebooks) return undefined;
    const scratch = notebooks.find((n) => n.kind === "scratchpad");
    return [...(scratch ? [scratch] : []), ...childrenOf(notebooks, null)];
  }, [notebooks]);

  const childBars = useMemo(() => {
    if (!notebooks || trail.length === 0) return [];
    const bars: Array<{ parentId: string; kids: Notebook[] }> = [];
    for (const parentId of trail) {
      const kids = childrenOf(notebooks, parentId);
      if (kids.length > 0) bars.push({ parentId, kids });
    }
    return bars;
  }, [notebooks, trail]);

  return (
    <div className="shrink-0 border-b bg-background">
      <NavBar
        ariaLabel="Notebooks"
        items={topLevel}
        selectedId={selectedId}
        onSelect={onSelect}
        trailing={
          <DropdownMenu>
            <DropdownMenuTrigger
              aria-label="Settings"
              className="ml-1 inline-flex size-7 shrink-0 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              <Settings className="size-4" />
            </DropdownMenuTrigger>
<<<<<<< HEAD
            <DropdownMenuContent align="end" className="w-52">
              <DropdownMenuGroup>
                <DropdownMenuLabel className="font-normal text-muted-foreground">
                  Read-only companion
                </DropdownMenuLabel>
              </DropdownMenuGroup>
=======
            <DropdownMenuContent align="end" className="w-56">
              <DropdownMenuLabel className="font-normal text-muted-foreground">
                Read-only companion
              </DropdownMenuLabel>
>>>>>>> 52b7b1b (v64: live-writer Hand off ceremony for Play cutover)
              <DropdownMenuSeparator />
              <DropdownMenuItem onClick={() => setHandOffOpen(true)}>
                Move to a new device…
              </DropdownMenuItem>
              <DropdownMenuSeparator />
              {onSignOut ? (
                <DropdownMenuItem onClick={onSignOut}>Sign out</DropdownMenuItem>
              ) : (
                <DropdownMenuItem disabled>Signed in via device</DropdownMenuItem>
              )}
            </DropdownMenuContent>
          </DropdownMenu>
        }
      />
      {childBars.map((bar) => (
        <NavBar
          key={bar.parentId}
          ariaLabel="Sub-notebooks"
          items={bar.kids}
          selectedId={selectedId}
          onSelect={onSelect}
          nested
        />
      ))}
      <HandOffDialog open={handOffOpen} onOpenChange={setHandOffOpen} />
    </div>
  );
}

function NavBar({
  ariaLabel,
  items,
  selectedId,
  onSelect,
  nested = false,
  trailing,
}: {
  ariaLabel: string;
  items: Notebook[] | undefined;
  selectedId: string | undefined;
  onSelect: (id: string) => void;
  nested?: boolean;
  trailing?: React.ReactNode;
}) {
  return (
    <div
      className={cn(
        "flex items-center gap-1 px-2 sm:px-3",
        nested ? "h-10 border-t border-foreground/15 bg-muted/50" : "h-11",
      )}
    >
      <nav aria-label={ariaLabel} className="min-w-0 flex-1 overflow-x-auto">
        <ul className="flex w-max items-center gap-0.5 py-1">
          {items === undefined ? (
            <>
              {Array.from({ length: 4 }, (_, i) => (
                <li key={i}>
                  <Skeleton className="h-7 w-20 rounded-sm" />
                </li>
              ))}
            </>
          ) : items.length === 0 ? (
            <li className="px-2 text-xs text-muted-foreground">
              No notebooks yet. Create them on the Boox.
            </li>
          ) : (
            items.map((nb, i) => {
              const selected = nb.id === selectedId;
              return (
                <li key={nb.id} className="flex items-center">
                  {i > 0 && !selected && items[i - 1]?.id !== selectedId && (
                    <span className="mx-0.5 text-muted-foreground/50 select-none" aria-hidden>
                      |
                    </span>
                  )}
                  <button
                    type="button"
                    onClick={() => onSelect(nb.id)}
                    className={cn(
                      "rounded-sm px-2.5 py-1 text-sm transition-colors",
                      selected
                        ? "bg-foreground font-medium text-background"
                        : "text-foreground hover:bg-muted",
                    )}
                  >
                    {nb.title}
                  </button>
                </li>
              );
            })
          )}
        </ul>
      </nav>
      {trailing}
    </div>
  );
}
