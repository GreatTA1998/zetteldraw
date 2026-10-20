"use client";

import { ChevronLeft, ChevronRight, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { type Page, type PageLink } from "@/lib/api";
import { dateTime } from "@/lib/format";
import { PAGE_ASPECT } from "@/components/page-grid";
import { PageInkCanvas } from "@/components/page-ink-canvas";

interface Props {
  pages: Page[];
  index: number | null;
  notebookTitle: string;
  /** Proxied Ready log URL for this notebook, or null when missing. */
  inkUrl: string | null;
  onIndexChange: (index: number | null) => void;
  onFollowLink: (link: PageLink) => void;
}

export function PageViewer({ pages, index, notebookTitle, inkUrl, onIndexChange, onFollowLink }: Props) {
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

            <div className="relative flex min-h-0 flex-1 items-center justify-center overflow-hidden px-2 pb-3">
              <div className="flex h-full max-h-full items-stretch justify-center gap-1">
                {prev ? (
                  <NeighborPeek
                    page={prev}
                    inkUrl={inkUrl}
                    side="left"
                    label={String(index)}
                    onClick={() => onIndexChange(index - 1)}
                  />
                ) : (
                  <div className="hidden w-14 shrink-0 sm:block" aria-hidden />
                )}
                <div
                  className="relative h-full max-h-full min-h-0 shrink-0 overflow-hidden rounded-sm bg-white shadow-2xl"
                  style={{ aspectRatio: PAGE_ASPECT }}
                >
                  <PageInkCanvas key={page.id} inkUrl={inkUrl} pageId={page.id} className="absolute inset-0 size-full" />
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
                    inkUrl={inkUrl}
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

function NeighborPeek({
  page,
  inkUrl,
  side,
  label,
  onClick,
}: {
  page: Page;
  inkUrl: string | null;
  side: "left" | "right";
  label: string;
  onClick: () => void;
}) {
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
        <PageInkCanvas inkUrl={inkUrl} pageId={page.id} compact className="absolute inset-0 size-full" />
        <span
          className={`absolute top-1 z-10 text-[0.65rem] tabular-nums text-neutral-500 ${
            side === "left" ? "left-1.5" : "right-1.5"
          }`}
        >
          {label}
        </span>
      </div>
    </button>
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
