"use client";

import { useEffect, useRef, useState } from "react";
import { KeyRound, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { api, ApiError } from "@/lib/api";

declare global {
  interface Window {
    google?: {
      accounts: {
        id: {
          initialize: (cfg: {
            client_id: string;
            callback: (response: { credential: string }) => void;
            auto_select?: boolean;
            cancel_on_tap_outside?: boolean;
          }) => void;
          renderButton: (
            parent: HTMLElement,
            options: { theme?: string; size?: string; width?: number; text?: string },
          ) => void;
        };
      };
    };
  }
}

function loadGis(): Promise<void> {
  if (typeof window === "undefined") return Promise.resolve();
  if (window.google?.accounts?.id) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>("script[data-zd-gis]");
    if (existing) {
      existing.addEventListener("load", () => resolve());
      existing.addEventListener("error", () => reject(new Error("GIS load failed")));
      return;
    }
    const script = document.createElement("script");
    script.src = "https://accounts.google.com/gsi/client";
    script.async = true;
    script.defer = true;
    script.dataset.zdGis = "1";
    script.onload = () => resolve();
    script.onerror = () => reject(new Error("GIS load failed"));
    document.head.appendChild(script);
  });
}

export function SignIn({
  onSignedIn,
  googleClientId,
}: {
  onSignedIn: () => void;
  googleClientId: string | null;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [token, setToken] = useState("");
  const buttonRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!googleClientId || !buttonRef.current) return;
    let cancelled = false;
    (async () => {
      try {
        await loadGis();
        if (cancelled || !buttonRef.current || !window.google) return;
        window.google.accounts.id.initialize({
          client_id: googleClientId,
          callback: async (response) => {
            setBusy(true);
            setError(null);
            try {
              await api.signInWithGoogle(response.credential);
              onSignedIn();
            } catch (err) {
              setError(err instanceof ApiError ? err.message : "Google sign-in failed.");
            } finally {
              setBusy(false);
            }
          },
          auto_select: false,
          cancel_on_tap_outside: true,
        });
        buttonRef.current.innerHTML = "";
        window.google.accounts.id.renderButton(buttonRef.current, {
          theme: "outline",
          size: "large",
          width: 320,
          text: "signin_with",
        });
      } catch {
        if (!cancelled) setError("Could not load Google Sign-In.");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [googleClientId, onSignedIn]);

  async function submitLegacy(e: React.FormEvent) {
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

  if (googleClientId) {
    return (
      <main className="flex min-h-dvh items-center justify-center bg-muted/40 p-6">
        <div className="w-full max-w-sm space-y-6 rounded-2xl border bg-background p-8 shadow-sm">
          <div className="space-y-2">
            <h1 className="text-xl font-semibold tracking-tight">zetteldraw</h1>
            <p className="text-sm text-muted-foreground">
              Sign in with the same Google account you use on the Boox. This site is read-only — browse notebooks and
              pages; drawing stays on the device.
            </p>
          </div>
          <div ref={buttonRef} className="flex min-h-10 justify-center" />
          {busy && (
            <p className="flex items-center justify-center gap-2 text-sm text-muted-foreground">
              <Loader2 className="size-4 animate-spin" /> Signing in…
            </p>
          )}
          {error && (
            <p role="alert" className="text-sm text-destructive">
              {error}
            </p>
          )}
        </div>
      </main>
    );
  }

  return (
    <main className="flex min-h-dvh items-center justify-center bg-muted/40 p-6">
      <form onSubmit={submitLegacy} className="w-full max-w-sm space-y-6 rounded-2xl border bg-background p-8 shadow-sm">
        <div className="space-y-2">
          <div className="flex size-10 items-center justify-center rounded-xl bg-primary text-primary-foreground">
            <KeyRound className="size-5" />
          </div>
          <h1 className="text-xl font-semibold tracking-tight">zetteldraw overview</h1>
          <p className="text-sm text-muted-foreground">
            Google Sign-In is not configured on this deployment (<code>NEXT_PUBLIC_GOOGLE_CLIENT_ID</code>). For local
            demos, enter a device token.
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
