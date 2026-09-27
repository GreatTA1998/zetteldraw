import "server-only";
import { cookies } from "next/headers";

export const TOKEN_COOKIE = "zd_token";

/** Where the sync server is, as seen from the Next.js server (not the browser). */
export function serverUrl(): string {
  return (process.env.ZD_SERVER_URL ?? "http://127.0.0.1:8787").replace(/\/+$/, "");
}

/**
 * The device token for this browser: the sign-in cookie, or ZD_DEVICE_TOKEN when
 * the operator wants the overview open without signing in (a trusted LAN screen).
 */
export async function deviceToken(): Promise<string | null> {
  const jar = await cookies();
  return jar.get(TOKEN_COOKIE)?.value || process.env.ZD_DEVICE_TOKEN || null;
}
