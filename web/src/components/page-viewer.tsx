"use client";

import { ChevronLeft, ChevronRight, X } from "lucide-react";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { proxied, type Page, type PageLink } from "@/lib/api";
import { dateTime } from "@/lib/format";
import { PAGE_ASPECT } from "@/components/page-grid";

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
        className="flex h-dvh w-screen max-w-none flex-col gap-0 rounded-none p-0 text-neutral-100 ring-0 sm:max-w-none"
        style={{ backgroundColor: "#1a1a1a" }}
      >
        {page && index !== null && (
          <>
            <header className="flex h-12 shrink-0 items-center gap-3 px-3 sm:px-4">
              <div className="min-w-0 flex-1">
                <DialogTitle className="truncate text-sm font-medium text-neutral-100">
                  {notebookTitle}{" "}
                  <span className="text-neutral-400">· {index + 1}</span>
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

            {/*
              Center page + narrow cropped peeks. Peeks are a clip window over a
              correctly aspect-ratio page (no stretch/perspective) — only a
              strip of the neighbor shows, tight against the center.
            */}
            <div className="relative flex min-h-0 flex-1 items-center justify-center overflow-hidden px-2 pb-3">
              <div className="flex h-full max-h-full items-stretch justify-center gap-1">
                {prev ? (
                  <NeighborPeek
                    page={prev}
                    side="left"
                    label={String(index)}
                    onClick={() => onIndexChange(index - 1)}
                  />
                ) : (
                  <div className="hidden w-14 shrink-0 sm:block" aria-hidden />
                )}
                <div
                  className="relative h-full max-h-full min-h-0 shrink-0"
                  style={{ aspectRatio: PAGE_ASPECT }}
                >
                  <FullPage key={page.id} page={page} />
                  {(page.links?.length ?? 0) > 0 && (
                    <div className="absolute bottom-3 left-3 z-20 flex max-w-[85%] flex-col items-start gap-0.5">
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
                {next ? (
                  <NeighborPeek
                    page={next}
                    side="right"
                    label={String(index + 2)}
                    onClick={() => onIndexChange(index + 1)}
                  />
                ) : (
                  <div className="hidden w-14 shrink-0 sm:block" aria-hidden />
                )}
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

/** Narrow clip over a correct-aspect page; shows the edge nearest the center. */
function NeighborPeek({
  page,
  side,
  label,
  onClick,
}: {
  page: Page;
  side: "left" | "right";
  label: string;
  onClick: () => void;
}) {
  const src = proxied(page.thumb_url);
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={side === "left" ? "Previous page" : "Next page"}
      className="relative hidden h-full w-14 shrink-0 overflow-hidden rounded-sm bg-white opacity-70 shadow-md transition hover:opacity-90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white/60 sm:block"
    >
      <div
        className="absolute inset-y-0 h-full bg-white"
        style={{
          aspectRatio: PAGE_ASPECT,
          ...(side === "left" ? { right: 0 } : { left: 0 }),
        }}
      >
        {src ? (
          // eslint-disable-next-line @next/next/no-img-element -- auth-proxied PNG
          <img src={src} alt="" draggable={false} className="size-full object-contain" />
        ) : (
          <div className="flex size-full items-center justify-center text-[0.65rem] text-neutral-400">Blank</div>
        )}
        <span
          className={`absolute top-1 text-[0.65rem] tabular-nums text-neutral-500 ${
            side === "left" ? "left-1.5" : "right-1.5"
          }`}
        >
          {label}
        </span>
      </div>
    </button>
  );
}

function FullPage({ page }: { page: Page }) {
  const [loaded, setLoaded] = useState(false);
  const [failed, setFailed] = useState(false);
  const thumb = proxied(page.thumb_url);
  const full = proxied(page.render_url);
  return (
    <div className="relative size-full overflow-hidden rounded-sm bg-white shadow-2xl">
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
