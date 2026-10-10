import { cookies } from "next/headers";
import { accessToken, googleClientId, serverUrl, sharedToken, TOKEN_COOKIE } from "@/lib/server-config";

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
  const shared = sharedToken() !== null;
  const google = googleClientId() !== null;
  const token = await accessToken();
  if (!token) return Response.json({ authenticated: false, shared, google });
  const status = await check(token);
  if (status === "unreachable") {
    return Response.json({ error: "server_unreachable" }, { status: 502 });
  }
  return Response.json({ authenticated: status === "ok", shared, google });
}

export async function POST(request: Request) {
  const body = (await request.json().catch(() => null)) as {
    id_token?: unknown;
    token?: unknown;
  } | null;

  const idToken = typeof body?.id_token === "string" ? body.id_token.trim() : "";
  const legacyToken = typeof body?.token === "string" ? body.token.trim() : "";

  let access: string | null = null;

  if (idToken) {
    try {
      const res = await fetch(`${serverUrl()}/auth/google`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ id_token: idToken, claim: true }),
        cache: "no-store",
      });
      if (res.status === 401) {
        return Response.json({ error: "unauthorized" }, { status: 401 });
      }
      if (!res.ok) {
        return Response.json({ error: "server_unreachable" }, { status: 502 });
      }
      const json = (await res.json()) as { access_token?: string };
      access = json.access_token ?? null;
    } catch {
      return Response.json({ error: "server_unreachable" }, { status: 502 });
    }
  } else if (legacyToken && !googleClientId()) {
    const status = await check(legacyToken);
    if (status === "unreachable") {
      return Response.json({ error: "server_unreachable" }, { status: 502 });
    }
    if (status === "unauthorized") {
      return Response.json({ error: "unauthorized" }, { status: 401 });
    }
    access = legacyToken;
  } else {
    return Response.json({ error: "missing_token" }, { status: 400 });
  }

  if (!access) {
    return Response.json({ error: "unauthorized" }, { status: 401 });
  }

  const jar = await cookies();
  jar.set(TOKEN_COOKIE, access, {
    httpOnly: true,
    sameSite: "lax",
    secure: request.url.startsWith("https:") || request.headers.get("x-forwarded-proto") === "https",
    path: "/",
    maxAge: 60 * 60 * 24 * 30,
  });
  return Response.json({ authenticated: true });
}

export async function DELETE() {
  const jar = await cookies();
  jar.delete(TOKEN_COOKIE);
  return Response.json({ authenticated: false });
}
