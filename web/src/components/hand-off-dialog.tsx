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
  const [busy, setBusy] = useState<"hand-off" | "cancel" | "lost-device" | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [confirmLost, setConfirmLost] = useState(false);

  async function run(action: "hand-off" | "cancel" | "lost-device") {
    setBusy(action);
    setActionError(null);
    try {
      const next =
        action === "hand-off"
          ? await api.handOffWriter()
          : action === "cancel"
            ? await api.cancelHandOff()
            : await api.ackLostDevice();
      await mutate(next, { revalidate: false });
      if (action === "lost-device") setConfirmLost(false);
    } catch (err) {
      setActionError(err instanceof Error ? err.message : "Request failed");
    } finally {
      setBusy(null);
    }
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) setConfirmLost(false);
        onOpenChange(next);
      }}
    >
      <DialogContent className="sm:max-w-lg" showCloseButton>
        <DialogHeader>
          <DialogTitle>Move to a new device</DialogTitle>
          <DialogDescription>
            One Boox writes. Cloud backs up. Prepare transfer on the live Boox, then Hand off here —
            like an iPhone upgrade.
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
            <Steps status={data} />
            {data.state === "live" && !data.transfer_prepared && !data.lost_device_ack && (
              <LostDeviceSecondary
                confirmLost={confirmLost}
                setConfirmLost={setConfirmLost}
                busy={busy}
                onConfirm={() => run("lost-device")}
              />
            )}
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
              {footerHint(data)}
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

function footerHint(data: WriterStatus | undefined): string {
  if (!data) return "";
  if (data.state === "none") return "No Hand off needed until a live Boox exists.";
  if (!data.backup_ready) return "Hand off stays locked until cloud backup is ready.";
  if (data.transfer_prepared) return "Transfer prepared — Hand off is unlocked.";
  if (data.lost_device_ack) return "Lost-device confirmed — Hand off is unlocked.";
  return "Hand off stays locked until the live Boox taps Prepare transfer.";
}

function Steps({ status }: { status: WriterStatus }) {
  if (status.state === "none") {
    return (
      <ol className="list-decimal space-y-1.5 pl-5 text-sm text-muted-foreground">
        <li>Install on a Boox, sign in with Google, wait until sync is caught up.</li>
        <li>That first device becomes the live writer automatically.</li>
        <li>When you later get a new Boox, use Prepare transfer → Hand off (below).</li>
      </ol>
    );
  }
  if (status.state === "handoff_open") {
    return (
      <ol className="list-decimal space-y-1.5 pl-5 text-sm text-muted-foreground">
        <li>Install Play (or the new tip) on the new Boox.</li>
        <li>Sign in with the same Google account and wait for sync.</li>
        <li>The new device claims live writer automatically. Old device stays blocked.</li>
      </ol>
    );
  }
  return (
    <ol className="list-decimal space-y-1.5 pl-5 text-sm text-muted-foreground">
      <li>
        On the <span className="text-foreground">live Boox</span>: Settings →{" "}
        <span className="text-foreground">Prepare transfer</span> (forces a final sync, then
        unlocks Hand off here).
      </li>
      <li>
        When this page shows transfer prepared + backup ready, tap{" "}
        <span className="text-foreground">Hand off</span> — the old Boox can no longer upload.
      </li>
      <li>
        Install Play (or the new tip), sign in with the same Google account, wait for sync. The new
        device claims live writer automatically.
      </li>
    </ol>
  );
}

/** Secondary path only — never the big primary button. */
function LostDeviceSecondary({
  confirmLost,
  setConfirmLost,
  busy,
  onConfirm,
}: {
  confirmLost: boolean;
  setConfirmLost: (v: boolean) => void;
  busy: string | null;
  onConfirm: () => void;
}) {
  if (!confirmLost) {
    return (
      <p className="text-xs text-muted-foreground">
        Lost the old Boox?{" "}
        <button
          type="button"
          className="underline underline-offset-2 hover:text-foreground"
          onClick={() => setConfirmLost(true)}
        >
          I no longer have the old device
        </button>
      </p>
    );
  }
  return (
    <div className="rounded-md border border-border/80 bg-muted/30 px-3 py-2.5 text-xs text-muted-foreground space-y-2">
      <p>
        This skips Prepare transfer on the old device. Only continue if that Boox is gone or wiped —
        any unsynced strokes on it will not reach the cloud.
      </p>
      <div className="flex flex-wrap gap-2">
        <Button
          size="sm"
          variant="outline"
          disabled={busy !== null}
          onClick={onConfirm}
        >
          {busy === "lost-device" ? "Confirming…" : "Confirm — old device is gone"}
        </Button>
        <Button size="sm" variant="ghost" disabled={busy !== null} onClick={() => setConfirmLost(false)}>
          Cancel
        </Button>
      </div>
    </div>
  );
}

function StatusBlock({ status }: { status: WriterStatus }) {
  const tone =
    status.state === "handoff_open"
      ? "border-amber-500/40 bg-amber-500/10"
      : status.state === "live" && status.can_hand_off
        ? "border-emerald-500/30 bg-emerald-500/10"
        : status.state === "live" && status.transfer_prepared
          ? "border-emerald-500/30 bg-emerald-500/10"
          : "border-border bg-muted/40";
  return (
    <div className={cn("rounded-lg border px-3 py-2.5 text-sm", tone)}>
      <p className="font-medium text-foreground">
        {status.state === "none" && "No live device yet"}
        {status.state === "live" &&
          (status.transfer_prepared
            ? `Transfer prepared · ${status.device_label}`
            : status.lost_device_ack
              ? `Lost-device path · ${status.device_label}`
              : `Live writer: ${status.device_label}`)}
        {status.state === "handoff_open" &&
          `Hand off open${status.handoff_until ? ` · ${untilLabel(status.handoff_until)}` : ""}`}
      </p>
      <p className="mt-1 text-muted-foreground">{status.note}</p>
      {status.last_seen_at !== null && status.state !== "none" && (
        <p className="mt-1 text-xs text-muted-foreground">
          Last seen {relative(status.last_seen_at)}
          {status.device_id ? ` · ${status.device_id.slice(0, 8)}…` : ""}
          {status.backup_ready ? " · backup ready" : " · backup not ready"}
          {status.transfer_prepared ? " · prepared" : ""}
          {status.lost_device_ack ? " · lost-device ack" : ""}
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
  const prepared = status.transfer_prepared || status.lost_device_ack;
  const mid = waiting
    ? "Hand off open — waiting for new device…"
    : none
      ? "first sign-in claims live writer"
      : prepared
        ? "Hand off unlocked → new device claims"
        : "Boox: Prepare transfer → then Hand off";
  return (
    <div
      aria-hidden
      className="rounded-lg border border-dashed border-border/80 bg-muted/20 px-3 py-3 font-mono text-[11px] leading-relaxed text-muted-foreground sm:text-xs"
    >
      <pre className="overflow-x-auto whitespace-pre">{`  ${none ? "[ no live Boox ]" : oldActive ? (prepared ? "[ Live Boox ]  prepared" : "[ Live Boox ]*") : "[ Old Boox ]"}
           |  ${none ? "push" : prepared || waiting ? "push blocked after Hand off" : "push · Prepare transfer"}
           v
      [ Cloud backup ]
           |
           ${mid}
           |
           v
  ${waiting ? "[ New Boox ]  ← pull, then claim*" : "[ New Boox ]"}`}</pre>
    </div>
  );
}
