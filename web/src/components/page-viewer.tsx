"use client";

import { ChevronLeft, ChevronRight, X } from "lucide-react";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { proxied, type Page } from "@/lib/api";
import { dateTime } from "@/lib/format";
import { PAGE_ASPECT } from "@/components/page-grid";

interface Props {
  pages: Page[];
  index: number | null;
  notebookTitle: string;
  onIndexChange: (index: number | null) => void;
}

export function PageViewer({ pages, index, notebookTitle, onIndexChange }: Props) {
  const open = index !== null && index < pages.length;
  const page = open ? pages[index] : null;

  // On the popup rather than window: the dialog keeps focus inside and its key handling stops propagation.
  const onKeyDown = (e: React.KeyboardEvent) => {
    if (!open) return;
    const last = pages.length - 1;
    const to =
      e.key === "ArrowRight" || e.key === "ArrowDown" || e.key === "PageDown"
        ? Math.min(last, index + 1)
        : e.key === "ArrowLeft" || e.key === "ArrowUp" || e.key === "PageUp"
          ? Math.max(0, index - 1)
          : e.key === "Home"
            ? 0
            : e.key === "End"
              ? last
              : null;
    if (to !== null) {
      e.preventDefault();
      onIndexChange(to);
    }
  };

  useEffect(() => {
    if (!open) return;
    for (const neighbour of [pages[index - 1], pages[index + 1]]) {
      const src = neighbour && proxied(neighbour.render_url);
      if (src) new Image().src = src;
    }
  }, [open, index, pages]);

  return (
    <Dialog open={open} onOpenChange={(next) => !next && onIndexChange(null)}>
      <DialogContent
        showCloseButton={false}
        onKeyDown={onKeyDown}
        className="flex h-dvh w-screen max-w-none flex-col gap-0 rounded-none bg-neutral-900 p-0 text-neutral-100 ring-0 sm:max-w-none"
      >
        {page && index !== null && (
          <>
            <header className="flex h-12 shrink-0 items-center gap-3 px-3 sm:px-4">
              <div className="min-w-0 flex-1">
                <DialogTitle className="truncate text-sm font-medium text-neutral-100">
                  {notebookTitle} <span className="text-neutral-400">· Page {index + 1} of {pages.length}</span>
                </DialogTitle>
                <DialogDescription className="text-xs text-neutral-400">Edited {dateTime(page.updated_at)}</DialogDescription>
              </div>
              <span className="hidden text-xs text-neutral-500 md:inline">← → to flip · Esc to close</span>
              <Button
                variant="ghost"
                size="icon"
                aria-label="Close"
                className="text-neutral-300 hover:bg-white/10 hover:text-white"
                onClick={() => onIndexChange(null)}
              >
                <X />
              </Button>
            </header>
            <div className="relative flex min-h-0 flex-1 items-center justify-center px-2 pb-3 sm:px-16">
              <FullPage key={page.id} page={page} />
              <NavButton side="left" disabled={index === 0} onClick={() => onIndexChange(index - 1)} />
              <NavButton side="right" disabled={index === pages.length - 1} onClick={() => onIndexChange(index + 1)} />
            </div>
          </>
        )}
      </DialogContent>
    </Dialog>
  );
}

function FullPage({ page }: { page: Page }) {
  const [loaded, setLoaded] = useState(false);
  const [failed, setFailed] = useState(false);
  const thumb = proxied(page.thumb_url);
  const full = proxied(page.render_url);
  return (
    <div
      className="relative h-full max-w-full overflow-hidden rounded-sm bg-white shadow-2xl"
      style={{ aspectRatio: PAGE_ASPECT }}
    >
      {thumb && !failed && (
        // eslint-disable-next-line @next/next/no-img-element -- auth-proxied PNG
        <img src={thumb} alt="" aria-hidden className="absolute inset-0 size-full object-contain" />
      )}
      {full && failed ? (
        <div className="absolute inset-0 flex items-center justify-center p-6 text-center text-sm text-neutral-500">
          This page&apos;s ink could not be rendered.
        </div>
      ) : full ? (
        // eslint-disable-next-line @next/next/no-img-element -- auth-proxied PNG
        <img
          src={full}
          alt="Page"
          onLoad={() => setLoaded(true)}
          onError={() => setFailed(true)}
          className="absolute inset-0 size-full object-contain transition-opacity duration-150"
          style={{ opacity: loaded ? 1 : 0 }}
        />
      ) : (
        <div className="absolute inset-0 flex items-center justify-center text-sm text-neutral-400">Blank page</div>
      )}
    </div>
  );
}

function NavButton({ side, disabled, onClick }: { side: "left" | "right"; disabled: boolean; onClick: () => void }) {
  const Icon = side === "left" ? ChevronLeft : ChevronRight;
  return (
    <Button
      variant="ghost"
      size="icon-lg"
      aria-label={side === "left" ? "Previous page" : "Next page"}
      disabled={disabled}
      onClick={onClick}
      className={`absolute top-1/2 -translate-y-1/2 rounded-full bg-black/40 text-white hover:bg-black/60 hover:text-white disabled:opacity-0 ${
        side === "left" ? "left-2 sm:left-4" : "right-2 sm:right-4"
      }`}
    >
      <Icon className="size-6" />
    </Button>
  );
}
