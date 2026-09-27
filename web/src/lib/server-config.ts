import "server-only";
import { cookies } from "next/headers";

export const TOKEN_COOKIE = "zd_token";

/** Where the sync server is, as seen from the Next.js server (not the browser). */
export function serverUrl(): string {
  return (process.env.ZD_SERVER_URL ?? "http://127.0.0.1:8787").replace(/\/+$/, "");
}

/**
 * Set on a public or wall-display deployment: every visitor uses this token and
 * there is no sign-in. It wins over any leftover sign-in cookie.
 */
export function sharedToken(): string | null {
  return process.env.ZD_DEVICE_TOKEN || null;
}

/** The device token for this request: the shared one, else the sign-in cookie. */
export async function deviceToken(): Promise<string | null> {
  const shared = sharedToken();
  if (shared) return shared;
  const jar = await cookies();
  return jar.get(TOKEN_COOKIE)?.value || null;
}
