import { cookies } from "next/headers";
import { deviceToken, serverUrl, TOKEN_COOKIE } from "@/lib/server-config";

async function check(token: string): Promise<"ok" | "unauthorized" | "unreachable"> {
  try {
    const res = await fetch(`${serverUrl()}/web/session`, {
      headers: { authorization: `Bearer ${token}` },
      cache: "no-store",
    });
    if (res.status === 401) return "unauthorized";
    return res.ok ? "ok" : "unreachable";
  } catch {
    return "unreachable";
  }
}

export async function GET() {
  const token = await deviceToken();
  if (!token) return Response.json({ authenticated: false });
  const status = await check(token);
  if (status === "unreachable") {
    return Response.json({ error: "server_unreachable" }, { status: 502 });
  }
  return Response.json({ authenticated: status === "ok" });
}

export async function POST(request: Request) {
  const body = (await request.json().catch(() => null)) as { token?: unknown } | null;
  const token = typeof body?.token === "string" ? body.token.trim() : "";
  if (!token) {
    return Response.json({ error: "missing_token" }, { status: 400 });
  }
  const status = await check(token);
  if (status === "unreachable") {
    return Response.json({ error: "server_unreachable" }, { status: 502 });
  }
  if (status === "unauthorized") {
    return Response.json({ error: "unauthorized" }, { status: 401 });
  }
  const jar = await cookies();
  jar.set(TOKEN_COOKIE, token, {
    httpOnly: true,
    sameSite: "lax",
    secure: request.url.startsWith("https:"),
    path: "/",
    maxAge: 60 * 60 * 24 * 365,
  });
  return Response.json({ authenticated: true });
}

export async function DELETE() {
  const jar = await cookies();
  jar.delete(TOKEN_COOKIE);
  return Response.json({ authenticated: false });
}
