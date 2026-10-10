import { createHmac, timingSafeEqual } from "node:crypto";
import { OAuth2Client } from "google-auth-library";
import type { GoogleUser } from "./accounts.js";

export type AuthPrincipal =
  | { kind: "google"; user: GoogleUser }
  | { kind: "device"; token: string };

export interface AuthConfig {
  deviceTokens: string[];
  /** Web + Android OAuth client IDs accepted as Google ID token audiences. */
  googleClientIds: string[];
  /** HMAC secret for long-lived access tokens issued after Google verify. */
  sessionSecret: string | undefined;
  /** Accept `test-google:<sub>:<email>` bearers (integration / unit tests only). */
  allowTestGoogleTokens: boolean;
  /**
   * After the shared library has been claimed, reject device-token auth on
   * /sync and /web so sideloaded APKs with a baked token cannot write the
   * claimed namespace.
   */
  rejectDeviceTokensAfterClaim: boolean;
}

const ACCESS_PREFIX = "zd1.";

function tokenMatches(presented: string, tokens: string[]): boolean {
  const a = Buffer.from(presented);
  let ok = false;
  for (const token of tokens) {
    const b = Buffer.from(token);
    if (a.length === b.length && timingSafeEqual(a, b)) {
      ok = true;
    }
  }
  return ok;
}

function b64url(buf: Buffer | string): string {
  const b = Buffer.isBuffer(buf) ? buf : Buffer.from(buf);
  return b.toString("base64url");
}

function fromB64url(s: string): Buffer {
  return Buffer.from(s, "base64url");
}

export function issueAccessToken(
  user: GoogleUser,
  secret: string,
  ttlSec = 60 * 60 * 24 * 30,
  now = Date.now(),
): { access_token: string; expires_in: number } {
  const exp = Math.floor(now / 1000) + ttlSec;
  const payload = b64url(JSON.stringify({ sub: user.id, email: user.email, exp }));
  const sig = b64url(createHmac("sha256", secret).update(payload).digest());
  return { access_token: `${ACCESS_PREFIX}${payload}.${sig}`, expires_in: ttlSec };
}

export function verifyAccessToken(token: string, secret: string, now = Date.now()): GoogleUser | null {
  if (!token.startsWith(ACCESS_PREFIX)) {
    return null;
  }
  const body = token.slice(ACCESS_PREFIX.length);
  const dot = body.lastIndexOf(".");
  if (dot < 0) {
    return null;
  }
  const payload = body.slice(0, dot);
  const sig = body.slice(dot + 1);
  const expected = b64url(createHmac("sha256", secret).update(payload).digest());
  const a = Buffer.from(sig);
  const b = Buffer.from(expected);
  if (a.length !== b.length || !timingSafeEqual(a, b)) {
    return null;
  }
  try {
    const data = JSON.parse(fromB64url(payload).toString("utf8")) as {
      sub?: unknown;
      email?: unknown;
      exp?: unknown;
    };
    if (typeof data.sub !== "string" || typeof data.exp !== "number") {
      return null;
    }
    if (data.exp * 1000 < now) {
      return null;
    }
    return {
      id: data.sub,
      email: typeof data.email === "string" ? data.email : null,
    };
  } catch {
    return null;
  }
}

function parseTestGoogleToken(token: string): GoogleUser | null {
  // test-google:<sub>:<email>
  if (!token.startsWith("test-google:")) {
    return null;
  }
  const rest = token.slice("test-google:".length);
  const colon = rest.indexOf(":");
  if (colon <= 0) {
    return null;
  }
  const id = rest.slice(0, colon);
  const email = rest.slice(colon + 1) || null;
  if (!id) {
    return null;
  }
  return { id, email };
}

export class AuthService {
  private readonly google: OAuth2Client | null;

  constructor(private readonly config: AuthConfig) {
    this.google = config.googleClientIds.length > 0 ? new OAuth2Client() : null;
  }

  googleEnabled(): boolean {
    return this.config.googleClientIds.length > 0 || this.config.allowTestGoogleTokens;
  }

  async verifyGoogleIdToken(idToken: string): Promise<GoogleUser | null> {
    if (this.config.allowTestGoogleTokens) {
      const test = parseTestGoogleToken(idToken);
      if (test) {
        return test;
      }
    }
    if (!this.google || this.config.googleClientIds.length === 0) {
      return null;
    }
    try {
      const ticket = await this.google.verifyIdToken({
        idToken,
        audience: this.config.googleClientIds,
      });
      const payload = ticket.getPayload();
      if (!payload?.sub) {
        return null;
      }
      return { id: payload.sub, email: payload.email ?? null };
    } catch {
      return null;
    }
  }

  async resolveBearer(
    authorization: string | undefined,
    opts: { libraryClaimed: boolean },
  ): Promise<AuthPrincipal | null> {
    const auth = authorization ?? "";
    const token = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
    if (!token) {
      return null;
    }

    if (this.config.sessionSecret) {
      const sessionUser = verifyAccessToken(token, this.config.sessionSecret);
      if (sessionUser) {
        return { kind: "google", user: sessionUser };
      }
    }

    if (token.includes(".") || token.startsWith("test-google:")) {
      const googleUser = await this.verifyGoogleIdToken(token);
      if (googleUser) {
        return { kind: "google", user: googleUser };
      }
    }

    if (tokenMatches(token, this.config.deviceTokens)) {
      if (this.config.rejectDeviceTokensAfterClaim && opts.libraryClaimed) {
        return null;
      }
      return { kind: "device", token };
    }
    return null;
  }
}
