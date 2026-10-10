"use client";

import { BookOpen, Inbox, LogOut } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import type { Notebook } from "@/lib/api";
import { cn } from "@/lib/utils";

interface Props {
  notebooks: Notebook[] | undefined;
  selectedId: string | undefined;
  onSelect: (id: string) => void;
  onSignOut?: () => void;
}

export function NotebookSidebar({ notebooks, selectedId, onSelect, onSignOut }: Props) {
  return (
    <div className="flex h-full flex-col">
      <div className="flex h-14 shrink-0 items-center gap-2 px-4">
        <span className="size-2.5 rounded-full bg-foreground" aria-hidden />
        <span className="font-semibold tracking-tight">zetteldraw</span>
      </div>
      <nav aria-label="Notebooks" className="flex-1 overflow-y-auto px-2 pb-2">
        {notebooks === undefined ? (
          <div className="space-y-1.5 px-2 pt-1">
            {Array.from({ length: 6 }, (_, i) => (
              <Skeleton key={i} className="h-8 w-full" />
            ))}
          </div>
        ) : (
          <ul className="space-y-0.5">
            {notebooks.map((nb, i) => (
              <li key={nb.id}>
                {i === 1 && (
                  <div className="px-2.5 pt-4 pb-1.5 text-xs font-medium text-muted-foreground">Notebooks</div>
                )}
                <NotebookItem
                  notebook={nb}
                  selected={nb.id === selectedId}
                  onSelect={() => onSelect(nb.id)}
                />
              </li>
            ))}
            {notebooks.length === 1 && (
              <li className="px-2.5 pt-4 text-xs text-muted-foreground">
                No notebooks yet. Notebooks you create on the Boox show up here.
              </li>
            )}
          </ul>
        )}
      </nav>
      {onSignOut && (
        <div className="border-t p-2">
          <Button variant="ghost" size="sm" className="w-full justify-start text-muted-foreground" onClick={onSignOut}>
            <LogOut />
            Sign out
          </Button>
        </div>
      )}
    </div>
  );
}

function NotebookItem({
  notebook,
  selected,
  onSelect,
}: {
  notebook: Notebook;
  selected: boolean;
  onSelect: () => void;
}) {
  const Icon = notebook.kind === "scratchpad" ? Inbox : BookOpen;
  return (
    <button
      type="button"
      onClick={onSelect}
      className={cn(
        "flex w-full items-center gap-2 rounded-md px-2.5 py-1.5 text-left text-sm transition-colors",
        selected ? "bg-accent font-medium text-accent-foreground" : "hover:bg-muted",
      )}
    >
      <Icon className="size-4 shrink-0 opacity-70" />
      <span className="min-w-0 flex-1 truncate">{notebook.title}</span>
      <span className="tabular-nums text-xs text-muted-foreground">{notebook.page_count}</span>
    </button>
  );
}
