import type { NextRequest } from "next/server";
import { accessToken, serverUrl } from "@/lib/server-config";

/**
 * Forwards Hand off / lost-device / cancel to the sync server. This is the only
 * intentional write path from the web companion — it moves the live-writer lease, never ink.
 */

const ACTIONS = new Set(["hand-off", "cancel", "lost-device"]);

type Ctx = { params: Promise<{ action: string }> };

export async function POST(request: NextRequest, ctx: Ctx) {
  const { action } = await ctx.params;
  if (!ACTIONS.has(action)) {
    return Response.json({ error: "not_found" }, { status: 404 });
  }
  const token = await accessToken();
  if (!token) {
    return Response.json({ error: "unauthorized" }, { status: 401 });
  }
  let upstream: Response;
  try {
    upstream = await fetch(`${serverUrl()}/web/writer/${action}`, {
      method: "POST",
      headers: {
        authorization: `Bearer ${token}`,
        "content-type": "application/json",
      },
      body: "{}",
      cache: "no-store",
    });
  } catch {
    return Response.json({ error: "server_unreachable" }, { status: 502 });
  }
  const text = await upstream.text();
  return new Response(text, {
    status: upstream.status,
    headers: { "content-type": upstream.headers.get("content-type") ?? "application/json" },
  });
}
