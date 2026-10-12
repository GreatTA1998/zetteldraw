"use client";

import { ChevronLeft, ChevronRight, X } from "lucide-react";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { proxied, type Page, type PageLink } from "@/lib/api";
import { dateTime } from "@/lib/format";
import { PAGE_ASPECT, PageImage } from "@/components/page-grid";
import { cn } from "@/lib/utils";

interface Props {
  pages: Page[];
  index: number | null;
  notebookTitle: string;
  onIndexChange: (index: number | null) => void;
  onFollowLink: (link: PageLink) => void;
}

export function PageViewer({ pages, index, notebookTitle, onIndexChange, onFollowLink }: Props) {
  const open = index !== null && index < pages.length;
  const page = open ? pages[index] : null;
  const prev = open && index > 0 ? pages[index - 1] : null;
  const next = open && index < pages.length - 1 ? pages[index + 1] : null;

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
    for (const neighbour of [pages[index - 1], pages[index + 1], pages[index - 2], pages[index + 2]]) {
      const src = neighbour && proxied(neighbour.render_url);
      if (src) new Image().src = src;
    }
  }, [open, index, pages]);

  return (
    <Dialog open={open} onOpenChange={(nextOpen) => !nextOpen && onIndexChange(null)}>
      <DialogContent
        showCloseButton={false}
        onKeyDown={onKeyDown}
        className="flex h-dvh w-screen max-w-none flex-col gap-0 rounded-none bg-neutral-850 p-0 text-neutral-100 ring-0 sm:max-w-none"
        style={{ backgroundColor: "#1a1a1a" }}
      >
        {page && index !== null && (
          <>
            <header className="flex h-12 shrink-0 items-center gap-3 px-3 sm:px-4">
              <div className="min-w-0 flex-1">
                <DialogTitle className="truncate text-sm font-medium text-neutral-100">
                  {notebookTitle}{" "}
                  <span className="text-neutral-400">
                    · {index + 1}/{pages.length}
                  </span>
                </DialogTitle>
                <DialogDescription className="text-xs text-neutral-400">
                  Edited {dateTime(page.updated_at)}
                </DialogDescription>
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

            {/* Neighbor peeks: partial prev/next beside the current page (Android continuous-scroll mental model). */}
            <div className="relative flex min-h-0 flex-1 items-center justify-center overflow-hidden px-1 pb-3 sm:px-4">
              <div className="flex h-full w-full max-w-[1600px] items-center justify-center gap-2 sm:gap-3">
                <NeighborPeek
                  page={prev}
                  side="left"
                  label={prev ? `${index}/${pages.length}` : undefined}
                  onClick={() => onIndexChange(index - 1)}
                />
                <div className="relative z-10 flex h-full min-w-0 flex-[1.35] flex-col items-center justify-center">
                  <FullPage key={page.id} page={page} />
                  {(page.links?.length ?? 0) > 0 && (
                    <div className="absolute bottom-3 left-3 z-20 flex max-w-[70%] flex-col items-start gap-0.5">
                      {(page.links ?? []).map((link) => (
                        <button
                          key={`${link.id}-${link.direction}`}
                          type="button"
                          onClick={() => onFollowLink(link)}
                          className="rounded-sm bg-white/95 px-2 py-1 text-left text-sm text-neutral-900 shadow-sm ring-1 ring-black/10 transition hover:bg-white"
                        >
                          <span className="font-bold">{link.direction === "out" ? "→" : "←"}</span>{" "}
                          {link.other_notebook_title} {link.other_index}/{link.other_count}
                        </button>
                      ))}
                    </div>
                  )}
                </div>
                <NeighborPeek
                  page={next}
                  side="right"
                  label={next ? `${index + 2}/${pages.length}` : undefined}
                  onClick={() => onIndexChange(index + 1)}
                />
              </div>
              <NavButton side="left" disabled={!prev} onClick={() => onIndexChange(index - 1)} />
              <NavButton side="right" disabled={!next} onClick={() => onIndexChange(index + 1)} />
            </div>
          </>
        )}
      </DialogContent>
    </Dialog>
  );
}

function NeighborPeek({
  page,
  side,
  label,
  onClick,
}: {
  page: Page | null;
  side: "left" | "right";
  label?: string;
  onClick: () => void;
}) {
  if (!page) {
    return <div className="pointer-events-none hidden h-full w-[14%] max-w-48 shrink-0 sm:block" aria-hidden />;
  }
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={side === "left" ? "Previous page" : "Next page"}
      className={cn(
        "relative hidden h-[78%] w-[16%] max-w-52 shrink-0 overflow-hidden rounded-sm bg-white shadow-lg transition hover:brightness-105 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white/60 sm:block",
        side === "left" ? "origin-right" : "origin-left",
      )}
    >
      <PageImage page={page} className="opacity-80" />
      {label && (
        <span className="absolute top-1 left-1.5 text-[0.65rem] tabular-nums text-neutral-500">{label}</span>
      )}
      <span
        className={cn(
          "pointer-events-none absolute inset-y-0 w-1/3 from-neutral-900/40 to-transparent",
          side === "left" ? "right-0 bg-gradient-to-l" : "left-0 bg-gradient-to-r",
        )}
        aria-hidden
      />
    </button>
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
      className={`absolute top-1/2 z-20 -translate-y-1/2 rounded-full bg-black/40 text-white hover:bg-black/60 hover:text-white disabled:opacity-0 sm:hidden ${
        side === "left" ? "left-2" : "right-2"
      }`}
    >
      <Icon className="size-6" />
    </Button>
  );
}
