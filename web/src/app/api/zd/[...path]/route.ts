import type { NextRequest } from "next/server";
import { accessToken, serverUrl } from "@/lib/server-config";

/**
 * Forwards GET /api/zd/<path> to the sync server's /web/<path> with the access
 * token from the sign-in cookie. Write methods are refused — the companion is
 * strictly read-only.
 */
const SAFE_SEGMENT = /^[A-Za-z0-9._-]+$/;
const PASS_HEADERS = ["content-type", "cache-control"];

type Ctx = { params: Promise<{ path: string[] }> };

async function forward(request: NextRequest, ctx: Ctx) {
  if (request.method !== "GET" && request.method !== "HEAD") {
    return Response.json(
      { error: "read_only", message: "The web companion cannot change notebooks or pages." },
      { status: 405 },
    );
  }
  const { path } = await ctx.params;
  if (path.length === 0 || !path.every((s: string) => SAFE_SEGMENT.test(s) && s !== "." && s !== "..")) {
    return Response.json({ error: "not_found" }, { status: 404 });
  }
  const token = await accessToken();
  if (!token) {
    return Response.json({ error: "unauthorized" }, { status: 401 });
  }
  let upstream: Response;
  try {
    upstream = await fetch(`${serverUrl()}/web/${path.join("/")}`, {
      method: request.method,
      headers: { authorization: `Bearer ${token}` },
      cache: "no-store",
    });
  } catch {
    return Response.json({ error: "server_unreachable" }, { status: 502 });
  }
  const headers = new Headers();
  for (const name of PASS_HEADERS) {
    const value = upstream.headers.get(name);
    if (value) headers.set(name, value);
  }
  return new Response(upstream.body, { status: upstream.status, headers });
}

export const GET = forward;
export const HEAD = forward;

export function POST() {
  return Response.json(
    { error: "read_only", message: "The web companion cannot change notebooks or pages." },
    { status: 405 },
  );
}

export function PATCH() {
  return Response.json(
    { error: "read_only", message: "The web companion cannot change notebooks or pages." },
    { status: 405 },
  );
}
