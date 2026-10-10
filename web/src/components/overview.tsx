"use client";

import { AlertCircle, Grid2x2, Grid3x3, Loader2, Menu, RefreshCw } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import useSWR from "swr";
import { NotebookSidebar } from "@/components/notebook-sidebar";
import { gridStyle, PageCard, PageGridSkeleton } from "@/components/page-grid";
import { PageViewer } from "@/components/page-viewer";
import { SignIn } from "@/components/sign-in";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Sheet, SheetContent, SheetTitle } from "@/components/ui/sheet";
import { Skeleton } from "@/components/ui/skeleton";
import { Slider } from "@/components/ui/slider";
import { api, ApiError, fetcher, SCRATCHPAD, type Notebook, type Page } from "@/lib/api";
import { pagesLabel, relativeTime } from "@/lib/format";

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

export function Overview({ googleClientId }: { googleClientId: string | null }) {
  const session = useSWR<{ authenticated: boolean; shared?: boolean; google?: boolean }>(
    "/api/session",
    fetcher,
    { revalidateOnFocus: false },
  );

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
    return (
      <SignIn
        googleClientId={googleClientId}
        onSignedIn={() => session.mutate({ authenticated: true, google: !!googleClientId }, { revalidate: false })}
      />
    );
  }
  return (
    <Library
      canSignOut
      onSignedOut={() => session.mutate({ authenticated: false }, { revalidate: false })}
    />
  );
}

function Library({ onSignedOut, canSignOut = false }: { onSignedOut: () => void; canSignOut?: boolean }) {
  const [selectedId, setSelectedId] = usePersistent<string>("zd.notebook", SCRATCHPAD);
  const [columns, setColumns] = usePersistent<number>("zd.columns", 8);
  const [viewerIndex, setViewerIndex] = useState<number | null>(null);
  const [sidebarOpen, setSidebarOpen] = useState(false);

  const paused = viewerIndex !== null;
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

  const refreshAll = useCallback(() => {
    notebooksRes.mutate();
    pagesRes.mutate();
  }, [notebooksRes, pagesRes]);

  async function signOut() {
    await api.signOut().catch(() => {});
    onSignedOut();
  }

  const libraryEmpty = notebooks !== undefined && notebooks.every((n) => n.page_count === 0);
  const refreshing = notebooksRes.isValidating || pagesRes.isValidating;

  const sidebar = (
    <NotebookSidebar
      notebooks={notebooks}
      selectedId={selected?.id}
      onSelect={select}
      onSignOut={canSignOut ? signOut : undefined}
    />
  );

  return (
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
                <span className="hidden shrink-0 text-sm text-muted-foreground sm:inline">
                  {pagesLabel(selected.page_count)}
                  {selected.last_edited_at !== null && ` · edited ${relativeTime(selected.last_edited_at)}`}
                  {" · read-only"}
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
            <div className="grid" style={gridStyle(columns)}>
              {pages.map((page, i) => (
                <PageCard key={page.id} page={page} index={i} compact={columns >= 10} onOpen={setViewerIndex} />
              ))}
            </div>
          )}
        </div>
      </main>

      <PageViewer
        pages={pages ?? []}
        index={viewerIndex}
        notebookTitle={selected?.title ?? ""}
        onIndexChange={setViewerIndex}
      />
    </div>
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
      <p className="mt-1 text-sm text-muted-foreground">Write in it on the Boox — this site is read-only.</p>
    </div>
  );
}

function EmptyLibrary() {
  return (
    <div className="mx-auto mt-16 max-w-md text-center">
      <h2 className="font-medium">Nothing synced yet</h2>
      <p className="mt-1 text-sm text-muted-foreground">
        Pages show up here after the Boox syncs with this server. It syncs about every 15 minutes, and whenever the app
        goes to the background.
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
