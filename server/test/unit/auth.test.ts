import { describe, expect, it } from "vitest";
import { AuthService, issueAccessToken, verifyAccessToken } from "../../src/auth.js";

describe("access tokens", () => {
  const secret = "test-session-secret";
  const user = { id: "google-sub-1", email: "elton@example.com" };

  it("round-trips", () => {
    const { access_token, expires_in } = issueAccessToken(user, secret, 3600, 1_700_000_000_000);
    expect(expires_in).toBe(3600);
    expect(verifyAccessToken(access_token, secret, 1_700_000_000_000)).toEqual(user);
  });

  it("rejects expired and tampered tokens", () => {
    const { access_token } = issueAccessToken(user, secret, 10, 1_700_000_000_000);
    expect(verifyAccessToken(access_token, secret, 1_700_000_020_000)).toBeNull();
    expect(verifyAccessToken(access_token.slice(0, -2) + "ab", secret, 1_700_000_000_000)).toBeNull();
    expect(verifyAccessToken("not-a-token", secret)).toBeNull();
  });
});

describe("AuthService test Google tokens", () => {
  const auth = new AuthService({
    deviceTokens: ["dev-device-token"],
    googleClientIds: [],
    sessionSecret: "secret",
    allowTestGoogleTokens: true,
    rejectDeviceTokensAfterClaim: true,
  });

  it("accepts test-google bearers and device tokens before claim", async () => {
    const google = await auth.resolveBearer("Bearer test-google:sub-a:a@example.com", {
      libraryClaimed: false,
    });
    expect(google).toEqual({ kind: "google", user: { id: "sub-a", email: "a@example.com" } });

    const device = await auth.resolveBearer("Bearer dev-device-token", { libraryClaimed: false });
    expect(device).toEqual({ kind: "device", token: "dev-device-token" });
  });

  it("rejects device tokens after claim when configured", async () => {
    const device = await auth.resolveBearer("Bearer dev-device-token", { libraryClaimed: true });
    expect(device).toBeNull();

    const google = await auth.resolveBearer("Bearer test-google:sub-a:a@example.com", {
      libraryClaimed: true,
    });
    expect(google?.kind).toBe("google");
  });

  it("verifies issued access tokens as Google principals", async () => {
    const issued = issueAccessToken({ id: "sub-b", email: null }, "secret");
    const principal = await auth.resolveBearer(`Bearer ${issued.access_token}`, { libraryClaimed: true });
    expect(principal).toEqual({ kind: "google", user: { id: "sub-b", email: null } });
  });
});
