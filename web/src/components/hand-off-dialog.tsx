"use client";

import { useState } from "react";
import useSWR from "swr";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { api, fetcher, type WriterStatus } from "@/lib/api";
import { cn } from "@/lib/utils";

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

function relative(ms: number | null): string {
  if (ms === null) return "—";
  const delta = Date.now() - ms;
  if (delta < 60_000) return "just now";
  if (delta < 3_600_000) return `${Math.floor(delta / 60_000)}m ago`;
  if (delta < 86_400_000) return `${Math.floor(delta / 3_600_000)}h ago`;
  return `${Math.floor(delta / 86_400_000)}d ago`;
}

function untilLabel(until: number | null): string {
  if (until === null) return "";
  const left = until - Date.now();
  if (left <= 0) return "expired";
  const hours = Math.ceil(left / 3_600_000);
  return hours >= 24 ? `${Math.ceil(hours / 24)}d left` : `${hours}h left`;
}

export function HandOffDialog({ open, onOpenChange }: Props) {
  const { data, error, isLoading, mutate } = useSWR<WriterStatus>(
    open ? api.writerKey : null,
    fetcher,
    { refreshInterval: open ? 8_000 : 0 },
  );
  const [busy, setBusy] = useState<"hand-off" | "cancel" | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  async function run(action: "hand-off" | "cancel") {
    setBusy(action);
    setActionError(null);
    try {
      const next =
        action === "hand-off" ? await api.handOffWriter() : await api.cancelHandOff();
      await mutate(next, { revalidate: false });
    } catch (err) {
      setActionError(err instanceof Error ? err.message : "Request failed");
    } finally {
      setBusy(null);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg" showCloseButton>
        <DialogHeader>
          <DialogTitle>Move to a new device</DialogTitle>
          <DialogDescription>
            One Boox writes. Cloud backs up. Hand off before Play cutover or a second device.
          </DialogDescription>
        </DialogHeader>

        {isLoading && !data ? (
          <p className="text-sm text-muted-foreground">Loading live-writer status…</p>
        ) : error ? (
          <p className="text-sm text-destructive">
            {error instanceof Error ? error.message : "Could not load writer status."}
          </p>
        ) : data ? (
          <div className="space-y-4">
            <StatusBlock status={data} />
            <HandoffDiagram status={data} />
            <ol className="list-decimal space-y-1.5 pl-5 text-sm text-muted-foreground">
              <li>
                On the current Boox, wait until sync is caught up and this page shows backup ready.
              </li>
              <li>
                Tap <span className="text-foreground">Hand off</span> — the old Boox can no longer
                upload.
              </li>
              <li>
                Install Play (or the new tip), sign in with the same Google account, wait for sync.
                The new device claims live writer automatically.
              </li>
            </ol>
            {actionError && <p className="text-sm text-destructive">{actionError}</p>}
          </div>
        ) : null}

        <DialogFooter className="sm:justify-between">
          {data?.state === "handoff_open" ? (
            <Button
              variant="outline"
              disabled={busy !== null}
              onClick={() => run("cancel")}
            >
              {busy === "cancel" ? "Cancelling…" : "Cancel Hand off"}
            </Button>
          ) : (
            <span className="text-xs text-muted-foreground">
              {data && !data.backup_ready
                ? "Hand off stays locked until cloud backup is ready."
                : data?.state === "none"
                  ? "No Hand off needed until a live Boox exists."
                  : "Safe cutover requires Hand off."}
            </span>
          )}
          {data?.state === "live" && (
            <Button
              disabled={!data.can_hand_off || busy !== null}
              onClick={() => run("hand-off")}
            >
              {busy === "hand-off" ? "Opening…" : "Hand off"}
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function StatusBlock({ status }: { status: WriterStatus }) {
  const tone =
    status.state === "handoff_open"
      ? "border-amber-500/40 bg-amber-500/10"
      : status.state === "live" && status.backup_ready
        ? "border-emerald-500/30 bg-emerald-500/10"
        : "border-border bg-muted/40";
  return (
    <div className={cn("rounded-lg border px-3 py-2.5 text-sm", tone)}>
      <p className="font-medium text-foreground">
        {status.state === "none" && "No live device yet"}
        {status.state === "live" && `Live writer: ${status.device_label}`}
        {status.state === "handoff_open" &&
          `Hand off open${status.handoff_until ? ` · ${untilLabel(status.handoff_until)}` : ""}`}
      </p>
      <p className="mt-1 text-muted-foreground">{status.note}</p>
      {status.last_seen_at !== null && status.state !== "none" && (
        <p className="mt-1 text-xs text-muted-foreground">
          Last seen {relative(status.last_seen_at)}
          {status.device_id ? ` · ${status.device_id.slice(0, 8)}…` : ""}
          {status.backup_ready ? " · backup ready" : " · backup not ready"}
        </p>
      )}
    </div>
  );
}

/** ASCII-style flow that stays readable without becoming a card collage. */
function HandoffDiagram({ status }: { status: WriterStatus }) {
  const oldActive = status.state === "live";
  const waiting = status.state === "handoff_open";
  const none = status.state === "none";
  return (
    <div
      aria-hidden
      className="rounded-lg border border-dashed border-border/80 bg-muted/20 px-3 py-3 font-mono text-[11px] leading-relaxed text-muted-foreground sm:text-xs"
    >
      <pre className="overflow-x-auto whitespace-pre">{`  ${none ? "[ no live Boox ]" : oldActive ? "[ Live Boox ]*" : "[ Old Boox ]"}
           |  push
           v
      [ Cloud backup ]
           |
           ${waiting ? "Hand off open — waiting for new device…" : none ? "first sign-in claims live writer" : "Hand off → new device claims"}
           |
           v
  ${waiting ? "[ New Boox ]  ← pull, then claim*" : "[ New Boox ]"}`}</pre>
    </div>
  );
}
