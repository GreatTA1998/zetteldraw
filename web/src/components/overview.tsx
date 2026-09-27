"use client";

import {
  closestCenter,
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  pointerWithin,
  useSensor,
  useSensors,
  type CollisionDetection,
  type DragEndEvent,
  type DragStartEvent,
} from "@dnd-kit/core";
import { rectSortingStrategy, SortableContext, sortableKeyboardCoordinates } from "@dnd-kit/sortable";
import { AlertCircle, Grid2x2, Grid3x3, Loader2, Menu, Pencil, RefreshCw } from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";
import { toast } from "sonner";
import useSWR, { useSWRConfig } from "swr";
import { NotebookSidebar } from "@/components/notebook-sidebar";
import { gridStyle, PageGridSkeleton, PageImage, SortablePageCard } from "@/components/page-grid";
import { PageViewer } from "@/components/page-viewer";
import { RenameDialog } from "@/components/rename-dialog";
import { SignIn } from "@/components/sign-in";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Sheet, SheetContent, SheetTitle } from "@/components/ui/sheet";
import { Skeleton } from "@/components/ui/skeleton";
import { Slider } from "@/components/ui/slider";
import { api, ApiError, fetcher, SCRATCHPAD, type Notebook, type Page } from "@/lib/api";
import { pagesLabel, relativeTime } from "@/lib/format";
import { reorder } from "@/lib/reorder";

const MIN_COLUMNS = 2;
const MAX_COLUMNS = 16;
const REFRESH_MS = 30_000;

type NotebooksResponse = { notebooks: Notebook[] };
type PagesResponse = { notebook_id: string; pages: Page[] };

function usePersistent<T>(key: string, initial: T): [T, (v: T) => void] {
  const [value, setValue] = useState<T>(() => {
    if (typeof window === "undefined") return initial;
    try {
      const raw = window.localStorage.getItem(key);
      return raw === null ? initial : (JSON.parse(raw) as T);
    } catch {
      return initial;
    }
  });
  const set = useCallback(
    (v: T) => {
      setValue(v);
      try {
        window.localStorage.setItem(key, JSON.stringify(v));
      } catch {}
    },
    [key],
  );
  return [value, set];
}

export function Overview() {
  const session = useSWR<{ authenticated: boolean; shared?: boolean }>("/api/session", fetcher, {
    revalidateOnFocus: false,
  });

  if (session.error) {
    return (
      <CenteredMessage
        title="Can't reach the zetteldraw server"
        body={session.error instanceof ApiError ? session.error.message : "Something went wrong."}
        onRetry={() => session.mutate()}
      />
    );
  }
  if (!session.data) {
    return (
      <div className="flex min-h-dvh items-center justify-center">
        <Loader2 className="size-5 animate-spin text-muted-foreground" aria-label="Loading" />
      </div>
    );
  }
  if (session.data.shared) {
    if (!session.data.authenticated) {
      return (
        <CenteredMessage
          title="The library is unavailable"
          body="The sync server rejected this site's device token (ZD_DEVICE_TOKEN)."
          onRetry={() => session.mutate()}
        />
      );
    }
    return <Library onSignedOut={() => session.mutate()} />;
  }
  if (!session.data.authenticated) {
    return <SignIn onSignedIn={() => session.mutate({ authenticated: true }, { revalidate: false })} />;
  }
  return (
    <Library
      canSignOut
      onSignedOut={() => session.mutate({ authenticated: false }, { revalidate: false })}
    />
  );
}

function Library({ onSignedOut, canSignOut = false }: { onSignedOut: () => void; canSignOut?: boolean }) {
  const { mutate: mutateKey } = useSWRConfig();
  const [selectedId, setSelectedId] = usePersistent<string>("zd.notebook", SCRATCHPAD);
  const [columns, setColumns] = usePersistent<number>("zd.columns", 8);
  const [viewerIndex, setViewerIndex] = useState<number | null>(null);
  const [renaming, setRenaming] = useState<Notebook | null>(null);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [sidebarOpen, setSidebarOpen] = useState(false);

  const paused = activeId !== null || viewerIndex !== null;
  const swrOptions = { refreshInterval: paused ? 0 : REFRESH_MS, keepPreviousData: true };

  const notebooksRes = useSWR<NotebooksResponse>(api.notebooksKey, fetcher, swrOptions);
  const notebooks = notebooksRes.data?.notebooks;
  const selected = notebooks?.find((n) => n.id === selectedId) ?? notebooks?.[0];
  const pagesKey = selected ? api.pagesKey(selected.id) : null;
  const pagesRes = useSWR<PagesResponse>(pagesKey, fetcher, swrOptions);
  const pages = pagesRes.data?.notebook_id === selected?.id ? pagesRes.data?.pages : undefined;

  const unauthorized = [notebooksRes.error, pagesRes.error].some((e) => e instanceof ApiError && e.status === 401);
  useEffect(() => {
    if (unauthorized) onSignedOut();
  }, [unauthorized, onSignedOut]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      if (viewerIndex !== null || target?.closest("input, textarea, [contenteditable=true], [role=dialog]")) return;
      if (e.key === "-" || e.key === "_") setColumns(Math.min(MAX_COLUMNS, columns + 1));
      if (e.key === "=" || e.key === "+") setColumns(Math.max(MIN_COLUMNS, columns - 1));
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [columns, setColumns, viewerIndex]);

  const select = useCallback(
    (id: string) => {
      setSelectedId(id);
      setViewerIndex(null);
      setSidebarOpen(false);
    },
    [setSelectedId],
  );

  const refreshAll = useCallback(
    (...extraPageKeys: string[]) => {
      notebooksRes.mutate();
      pagesRes.mutate();
      for (const key of extraPageKeys) mutateKey(key);
    },
    [notebooksRes, pagesRes, mutateKey],
  );

  const moveToNotebook = useCallback(
    async (page: Page, target: Notebook) => {
      if (!selected || !pages || target.id === selected.id) return;
      pagesRes.mutate({ notebook_id: selected.id, pages: pages.filter((p) => p.id !== page.id) }, { revalidate: false });
      if (notebooks) {
        notebooksRes.mutate(
          {
            notebooks: notebooks.map((n) =>
              n.id === selected.id
                ? { ...n, page_count: n.page_count - 1 }
                : n.id === target.id
                  ? { ...n, page_count: n.page_count + 1 }
                  : n,
            ),
          },
          { revalidate: false },
        );
      }
      try {
        await api.movePage(page.id, target.id);
        toast.success(`Moved to ${target.title}`, {
          action: { label: "Show", onClick: () => select(target.id) },
        });
      } catch (err) {
        toast.error(err instanceof ApiError ? err.message : "Could not move the page.");
      } finally {
        refreshAll(api.pagesKey(target.id));
      }
    },
    [selected, pages, notebooks, pagesRes, notebooksRes, refreshAll, select],
  );

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
      keyboardCodes: { start: ["Space"], cancel: ["Escape"], end: ["Space", "Enter"] },
    }),
  );

  const collision: CollisionDetection = useCallback((args) => {
    const hits = pointerWithin(args);
    const notebookHit = hits.find((h) => String(h.id).startsWith("nb:"));
    if (notebookHit) return [notebookHit];
    return closestCenter({
      ...args,
      droppableContainers: args.droppableContainers.filter((c) => !String(c.id).startsWith("nb:")),
    });
  }, []);

  function onDragStart(e: DragStartEvent) {
    setActiveId(String(e.active.id));
  }

  async function onDragEnd({ active, over }: DragEndEvent) {
    setActiveId(null);
    if (!over || !selected || !pages) return;
    const pageId = String(active.id);
    const overId = String(over.id);
    if (overId.startsWith("nb:")) {
      const target = notebooks?.find((n) => n.id === overId.slice(3));
      const page = pages.find((p) => p.id === pageId);
      if (target && page) await moveToNotebook(page, target);
      return;
    }
    const result = reorder(pages, pageId, overId);
    if (!result) return;
    pagesRes.mutate({ notebook_id: selected.id, pages: result.pages }, { revalidate: false });
    try {
      await api.movePage(pageId, selected.id, result.afterId);
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Could not reorder the page.");
    } finally {
      pagesRes.mutate();
    }
  }

  async function rename(notebook: Notebook, title: string) {
    try {
      await api.renameNotebook(notebook.id, title);
      toast.success(`Renamed to ${title}`);
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Could not rename the notebook.");
      throw err;
    } finally {
      notebooksRes.mutate();
    }
  }

  async function signOut() {
    await api.signOut().catch(() => {});
    onSignedOut();
  }

  const moveTargets = useMemo(
    () => (notebooks ?? []).filter((n) => n.id !== selected?.id),
    [notebooks, selected?.id],
  );
  const pageIds = useMemo(() => (pages ?? []).map((p) => p.id), [pages]);
  const activePage = activeId ? pages?.find((p) => p.id === activeId) : undefined;
  const libraryEmpty = notebooks !== undefined && notebooks.every((n) => n.page_count === 0);
  const refreshing = notebooksRes.isValidating || pagesRes.isValidating;

  const sidebar = (
    <NotebookSidebar
      notebooks={notebooks}
      selectedId={selected?.id}
      dragging={activeId !== null}
      onSelect={select}
      onRename={setRenaming}
      onSignOut={canSignOut ? signOut : undefined}
    />
  );

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={collision}
      onDragStart={onDragStart}
      onDragEnd={onDragEnd}
      onDragCancel={() => setActiveId(null)}
    >
      <div className="flex h-dvh overflow-hidden bg-background">
        <aside className="hidden w-60 shrink-0 border-r bg-sidebar lg:block xl:w-64">{sidebar}</aside>
        <Sheet open={sidebarOpen} onOpenChange={setSidebarOpen}>
          <SheetContent side="left" className="w-72 p-0" showCloseButton={false}>
            <SheetTitle className="sr-only">Notebooks</SheetTitle>
            {sidebar}
          </SheetContent>
        </Sheet>

        <main className="flex min-w-0 flex-1 flex-col">
          <header className="flex h-14 shrink-0 items-center gap-2 border-b px-3 sm:gap-3 sm:px-5">
            <Button
              variant="ghost"
              size="icon"
              className="lg:hidden"
              aria-label="Show notebooks"
              onClick={() => setSidebarOpen(true)}
            >
              <Menu />
            </Button>
            <div className="min-w-0 flex-1">
              {selected ? (
                <div className="flex items-baseline gap-2">
                  <h1 className="truncate text-base font-semibold tracking-tight sm:text-lg">{selected.title}</h1>
                  {selected.kind === "notebook" && (
                    <Button
                      variant="ghost"
                      size="icon-xs"
                      aria-label={`Rename ${selected.title}`}
                      className="self-center text-muted-foreground"
                      onClick={() => setRenaming(selected)}
                    >
                      <Pencil />
                    </Button>
                  )}
                  <span className="hidden shrink-0 text-sm text-muted-foreground sm:inline">
                    {pagesLabel(selected.page_count)}
                    {selected.last_edited_at !== null && ` · edited ${relativeTime(selected.last_edited_at)}`}
                  </span>
                </div>
              ) : (
                <Skeleton className="h-5 w-48" />
              )}
            </div>
            <Button
              variant="ghost"
              size="icon-sm"
              aria-label="Refresh"
              className="text-muted-foreground"
              onClick={() => refreshAll()}
            >
              <RefreshCw className={refreshing ? "animate-spin" : undefined} />
            </Button>
            <div className="flex w-36 items-center gap-2 sm:w-56" title="Columns (− / = keys)">
              <Grid2x2 className="size-4 shrink-0 text-muted-foreground" aria-hidden />
              <Slider
                aria-label="Columns"
                min={MIN_COLUMNS}
                max={MAX_COLUMNS}
                step={1}
                value={columns}
                onValueChange={(v) => setColumns(Array.isArray(v) ? v[0] : v)}
              />
              <Grid3x3 className="size-4 shrink-0 text-muted-foreground" aria-hidden />
              <span className="w-5 text-right text-xs tabular-nums text-muted-foreground">{columns}</span>
            </div>
          </header>

          <div className="flex-1 overflow-y-auto bg-muted/50 p-3 sm:p-5">
            {notebooksRes.error && !unauthorized ? (
              <LoadError error={notebooksRes.error} onRetry={() => refreshAll()} />
            ) : pagesRes.error && !unauthorized ? (
              <LoadError error={pagesRes.error} onRetry={() => pagesRes.mutate()} />
            ) : !pages ? (
              <PageGridSkeleton columns={columns} />
            ) : libraryEmpty ? (
              <EmptyLibrary />
            ) : pages.length === 0 ? (
              <EmptyNotebook title={selected?.title ?? "this notebook"} />
            ) : (
              <SortableContext items={pageIds} strategy={rectSortingStrategy}>
                <div className="grid" style={gridStyle(columns)}>
                  {pages.map((page, i) => (
                    <SortablePageCard
                      key={page.id}
                      page={page}
                      index={i}
                      compact={columns >= 10}
                      moveTargets={moveTargets}
                      onOpen={setViewerIndex}
                      onMove={moveToNotebook}
                    />
                  ))}
                </div>
              </SortableContext>
            )}
          </div>
        </main>
      </div>

      <DragOverlay dropAnimation={null}>
        {activePage && (
          <div className="w-32 rotate-2 rounded-md shadow-2xl ring-1 ring-black/20">
            <PageImage page={activePage} className="rounded-md" />
          </div>
        )}
      </DragOverlay>

      <PageViewer
        pages={pages ?? []}
        index={viewerIndex}
        notebookTitle={selected?.title ?? ""}
        onIndexChange={setViewerIndex}
      />
      <RenameDialog notebook={renaming} onClose={() => setRenaming(null)} onRename={rename} />
    </DndContext>
  );
}

function LoadError({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  return (
    <Alert variant="destructive" className="mx-auto max-w-lg bg-background">
      <AlertCircle />
      <AlertTitle>Couldn&apos;t load pages</AlertTitle>
      <AlertDescription>
        <p>{error instanceof ApiError ? error.message : "Something went wrong."}</p>
        <Button variant="outline" size="sm" className="mt-2" onClick={onRetry}>
          Try again
        </Button>
      </AlertDescription>
    </Alert>
  );
}

function EmptyNotebook({ title }: { title: string }) {
  return (
    <div className="mx-auto mt-16 max-w-sm text-center">
      <div className="mx-auto mb-4 grid w-24 grid-cols-3 gap-1 opacity-40" aria-hidden>
        {Array.from({ length: 6 }, (_, i) => (
          <div key={i} className="rounded-sm border border-dashed border-foreground/40" style={{ aspectRatio: "3/4" }} />
        ))}
      </div>
      <h2 className="font-medium">No pages in {title} yet</h2>
      <p className="mt-1 text-sm text-muted-foreground">
        Write in it on the Boox, or drag a page from another notebook onto {title} in the sidebar.
      </p>
    </div>
  );
}

function EmptyLibrary() {
  return (
    <div className="mx-auto mt-16 max-w-md text-center">
      <h2 className="font-medium">Nothing synced yet</h2>
      <p className="mt-1 text-sm text-muted-foreground">
        Pages show up here after the Boox syncs with this server. It syncs about every 15 minutes, and whenever the
        app goes to the background.
      </p>
    </div>
  );
}

function CenteredMessage({ title, body, onRetry }: { title: string; body: string; onRetry: () => void }) {
  return (
    <main className="flex min-h-dvh items-center justify-center p-6">
      <div className="max-w-sm text-center">
        <AlertCircle className="mx-auto mb-3 size-6 text-destructive" />
        <h1 className="font-medium">{title}</h1>
        <p className="mt-1 text-sm text-muted-foreground">{body}</p>
        <Button variant="outline" className="mt-4" onClick={onRetry}>
          Try again
        </Button>
      </div>
    </main>
  );
}
