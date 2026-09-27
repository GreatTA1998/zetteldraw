"use client";

import { Loader2 } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle, DialogDescription } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import type { Notebook } from "@/lib/api";

interface Props {
  notebook: Notebook | null;
  onClose: () => void;
  onRename: (notebook: Notebook, title: string) => Promise<void>;
}

export function RenameDialog({ notebook, onClose, onRename }: Props) {
  return (
    <Dialog open={notebook !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        {notebook && <RenameForm key={notebook.id} notebook={notebook} onClose={onClose} onRename={onRename} />}
      </DialogContent>
    </Dialog>
  );
}

function RenameForm({ notebook, onClose, onRename }: { notebook: Notebook } & Omit<Props, "notebook">) {
  const [title, setTitle] = useState(notebook.title);
  const [busy, setBusy] = useState(false);
  const trimmed = title.trim();

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!trimmed || trimmed === notebook.title) {
      onClose();
      return;
    }
    setBusy(true);
    try {
      await onRename(notebook, trimmed);
      onClose();
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="grid gap-4">
      <DialogHeader>
        <DialogTitle>Rename notebook</DialogTitle>
        <DialogDescription>The Boox picks up the new name on its next sync.</DialogDescription>
      </DialogHeader>
      <Input
        autoFocus
        value={title}
        maxLength={200}
        onChange={(e) => setTitle(e.target.value)}
        onFocus={(e) => e.currentTarget.select()}
        aria-label="Notebook name"
      />
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onClose}>
          Cancel
        </Button>
        <Button type="submit" disabled={busy || !trimmed}>
          {busy && <Loader2 className="animate-spin" />}
          Save
        </Button>
      </DialogFooter>
    </form>
  );
}
