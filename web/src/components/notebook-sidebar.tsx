"use client";

import { useDroppable } from "@dnd-kit/core";
import { BookOpen, Inbox, LogOut, MoreHorizontal, Pencil } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Skeleton } from "@/components/ui/skeleton";
import type { Notebook } from "@/lib/api";
import { cn } from "@/lib/utils";

interface Props {
  notebooks: Notebook[] | undefined;
  selectedId: string | undefined;
  dragging: boolean;
  onSelect: (id: string) => void;
  onRename: (notebook: Notebook) => void;
  onSignOut: () => void;
}

export function NotebookSidebar({ notebooks, selectedId, dragging, onSelect, onRename, onSignOut }: Props) {
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
                  dragging={dragging}
                  onSelect={() => onSelect(nb.id)}
                  onRename={() => onRename(nb)}
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
      <div
        className={cn(
          "mx-2 mb-2 rounded-lg border border-dashed px-3 py-2 text-xs text-muted-foreground transition-opacity",
          dragging ? "opacity-100" : "pointer-events-none h-0 overflow-hidden border-0 p-0 opacity-0",
        )}
      >
        Drop on a notebook to move the page there.
      </div>
      <div className="border-t p-2">
        <Button variant="ghost" size="sm" className="w-full justify-start text-muted-foreground" onClick={onSignOut}>
          <LogOut />
          Sign out
        </Button>
      </div>
    </div>
  );
}

function NotebookItem({
  notebook,
  selected,
  dragging,
  onSelect,
  onRename,
}: {
  notebook: Notebook;
  selected: boolean;
  dragging: boolean;
  onSelect: () => void;
  onRename: () => void;
}) {
  const { setNodeRef, isOver } = useDroppable({ id: `nb:${notebook.id}`, disabled: selected });
  const Icon = notebook.kind === "scratchpad" ? Inbox : BookOpen;
  return (
    <div
      ref={setNodeRef}
      className={cn(
        "group relative flex items-center rounded-lg transition-colors",
        selected ? "bg-accent text-accent-foreground" : "hover:bg-muted",
        dragging && !selected && "ring-1 ring-border ring-inset",
        isOver && "bg-primary text-primary-foreground ring-primary hover:bg-primary",
      )}
    >
      <button
        type="button"
        onClick={onSelect}
        aria-current={selected ? "page" : undefined}
        className="flex min-w-0 flex-1 items-center gap-2.5 rounded-lg px-2.5 py-1.5 text-left text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring"
      >
        <Icon className="size-4 shrink-0 opacity-70" />
        <span className={cn("truncate", selected && "font-medium")}>{notebook.title}</span>
        <span
          className={cn(
            "ml-auto shrink-0 text-xs tabular-nums text-muted-foreground",
            isOver && "text-primary-foreground",
            notebook.kind === "notebook" && "group-hover:opacity-0 group-has-[[aria-expanded=true]]:opacity-0",
          )}
        >
          {notebook.page_count}
        </span>
      </button>
      {notebook.kind === "notebook" && (
        <DropdownMenu>
          <DropdownMenuTrigger
            render={
              <Button
                variant="ghost"
                size="icon-xs"
                aria-label={`Actions for ${notebook.title}`}
                className="absolute right-1.5 opacity-0 group-hover:opacity-100 focus-visible:opacity-100 aria-expanded:opacity-100"
              />
            }
          >
            <MoreHorizontal />
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start">
            <DropdownMenuItem onClick={onRename}>
              <Pencil />
              Rename
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      )}
    </div>
  );
}
