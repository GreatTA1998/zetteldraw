"use client";

import { useState } from "react";
import { KeyRound, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { api, ApiError } from "@/lib/api";

export function SignIn({ onSignedIn }: { onSignedIn: () => void }) {
  const [token, setToken] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.signIn(token);
      onSignedIn();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Sign-in failed.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="flex min-h-dvh items-center justify-center bg-muted/40 p-6">
      <form onSubmit={submit} className="w-full max-w-sm space-y-6 rounded-2xl border bg-background p-8 shadow-sm">
        <div className="space-y-2">
          <div className="flex size-10 items-center justify-center rounded-xl bg-primary text-primary-foreground">
            <KeyRound className="size-5" />
          </div>
          <h1 className="text-xl font-semibold tracking-tight">zetteldraw overview</h1>
          <p className="text-sm text-muted-foreground">
            Enter the device token your Boox syncs with. It is the same value as <code>DEVICE_TOKENS</code> on the
            server.
          </p>
        </div>
        <div className="space-y-2">
          <Label htmlFor="token">Device token</Label>
          <Input
            id="token"
            type="password"
            autoComplete="current-password"
            autoFocus
            value={token}
            onChange={(e) => setToken(e.target.value)}
            placeholder="dev-device-token"
            aria-invalid={error ? true : undefined}
          />
          {error && (
            <p role="alert" className="text-sm text-destructive">
              {error}
            </p>
          )}
        </div>
        <Button type="submit" size="lg" className="w-full" disabled={busy || token.trim() === ""}>
          {busy && <Loader2 className="animate-spin" />}
          Open library
        </Button>
      </form>
    </main>
  );
}
