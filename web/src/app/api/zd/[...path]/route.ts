import type { NextRequest } from "next/server";
import { deviceToken, serverUrl } from "@/lib/server-config";

/**
 * Forwards /api/zd/<path> to the sync server's /web/<path> with the device token
 * from the sign-in cookie, so <img> tags and fetches work without exposing the
 * token or the server to the browser.
 */
const SAFE_SEGMENT = /^[A-Za-z0-9._-]+$/;
const PASS_HEADERS = ["content-type", "cache-control"];

async function forward(request: NextRequest, ctx: RouteContext<"/api/zd/[...path]">) {
  const { path } = await ctx.params;
  if (path.length === 0 || !path.every((s) => SAFE_SEGMENT.test(s) && s !== "." && s !== "..")) {
    return Response.json({ error: "not_found" }, { status: 404 });
  }
  const token = await deviceToken();
  if (!token) {
    return Response.json({ error: "unauthorized" }, { status: 401 });
  }
  const hasBody = request.method !== "GET" && request.method !== "HEAD";
  let upstream: Response;
  try {
    upstream = await fetch(`${serverUrl()}/web/${path.join("/")}`, {
      method: request.method,
      headers: {
        authorization: `Bearer ${token}`,
        ...(hasBody ? { "content-type": "application/json" } : {}),
      },
      body: hasBody ? await request.text() : undefined,
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
export const POST = forward;
export const PATCH = forward;
