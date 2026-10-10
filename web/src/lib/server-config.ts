import "server-only";
import { cookies } from "next/headers";

/** Cookie holding the sync-server access token (zd1.* or Google ID token). */
export const TOKEN_COOKIE = "zd_token";

/** Where the sync server is, as seen from the Next.js server (not the browser). */
export function serverUrl(): string {
  return (process.env.ZD_SERVER_URL ?? "http://127.0.0.1:8787").replace(/\/+$/, "");
}

/** Web OAuth client ID for Google Identity Services (public). */
export function googleClientId(): string | null {
  return process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID?.trim() || null;
}

/**
 * Legacy shared device token. Only used when NEXT_PUBLIC_GOOGLE_CLIENT_ID is
 * unset (local demos). Production Google Sign-In ignores this.
 */
export function sharedToken(): string | null {
  if (googleClientId()) {
    return null;
  }
  return process.env.ZD_DEVICE_TOKEN || null;
}

/** Bearer for this request: shared token (legacy) or the sign-in cookie. */
export async function accessToken(): Promise<string | null> {
  const shared = sharedToken();
  if (shared) return shared;
  const jar = await cookies();
  return jar.get(TOKEN_COOKIE)?.value || null;
}

/** @deprecated use accessToken */
export async function deviceToken(): Promise<string | null> {
  return accessToken();
}
