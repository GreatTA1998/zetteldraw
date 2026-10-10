import { randomUUID } from "node:crypto";
import { beforeAll, describe, expect, it } from "vitest";

/**
 * Google auth + per-user isolation + claim. Prefer running alone on a fresh
 * compose volume (see scripts/integration.sh) so a claim does not empty the
 * unowned namespace that device-token tests rely on.
 */
const URL_BASE = process.env.SYNC_URL ?? "http://127.0.0.1:8787";

let schema = 0;

async function call(method: string, path: string, body?: unknown, token?: string) {
  const res = await fetch(URL_BASE + path, {
    method,
    headers: {
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(path.startsWith("/sync/") ? { "x-zetteldraw-schema": String(schema) } : {}),
      ...(body ? { "content-type": "application/json" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const type = res.headers.get("content-type") ?? "";
  return {
    status: res.status,
    json: type.includes("json") ? ((await res.json()) as any) : null,
  };
}

beforeAll(async () => {
  const health = await fetch(URL_BASE + "/healthz");
  schema = ((await health.json()) as { schema_version: number }).schema_version;
});

describe("Google auth + claim", () => {
  const userA = `test-google:claim-a-${randomUUID()}:a@example.com`;
  const userB = `test-google:claim-b-${randomUUID()}:b@example.com`;
  const notebookId = randomUUID();
  const now = Date.now();

  it("isolates namespaces and claims unowned rows once", async () => {
    const authA = await call("POST", "/auth/google", { id_token: userA, claim: false });
    expect(authA.status).toBe(200);
    expect(authA.json.user.email).toBe("a@example.com");
    const tokenA = authA.json.access_token as string;

    // Unowned seed via device token (compose leaves REJECT_DEVICE_TOKENS_AFTER_CLAIM=false).
    const deviceToken = process.env.SYNC_TOKEN ?? "dev-device-token";
    const seed = await call(
      "POST",
      "/sync/push",
      {
        schema_version: schema,
        device_id: "claim-seed",
        notebooks: [
          {
            id: notebookId,
            title: "Legacy shared note",
            position: `claim${now.toString(36)}`,
            created_at: now,
            updated_at: now,
            deleted_at: null,
            base_rev: 0,
          },
        ],
        boards: [],
        blobs: {},
      },
      deviceToken,
    );
    expect(seed.status).toBe(200);

    const claim = await call("POST", "/auth/claim", undefined, tokenA);
    expect(claim.status).toBe(200);
    expect(["claimed", "already_yours", "nothing_to_claim"]).toContain(claim.json.claim.status);

    const booksA = await call("GET", "/web/notebooks", undefined, tokenA);
    expect(booksA.status).toBe(200);
    expect(booksA.json.notebooks.some((n: { id: string }) => n.id === notebookId)).toBe(true);

    const again = await call("POST", "/auth/google", { id_token: userA });
    expect(again.json.claim.status).toBe("already_yours");

    const authB = await call("POST", "/auth/google", { id_token: userB });
    expect(authB.status).toBe(200);
    expect(authB.json.claim.status).toBe("already_taken");
    const booksB = await call("GET", "/web/notebooks", undefined, authB.json.access_token);
    expect(booksB.status).toBe(200);
    expect(booksB.json.notebooks.some((n: { id: string }) => n.id === notebookId)).toBe(false);

    // User B can create their own notebook without seeing A's.
    const bNote = randomUUID();
    const pushB = await call(
      "POST",
      "/sync/push",
      {
        schema_version: schema,
        device_id: "user-b",
        notebooks: [
          {
            id: bNote,
            title: "B only",
            position: `b${now.toString(36)}`,
            created_at: now,
            updated_at: now,
            deleted_at: null,
            base_rev: 0,
          },
        ],
        boards: [],
        blobs: {},
      },
      authB.json.access_token,
    );
    expect(pushB.status).toBe(200);
    expect(pushB.json.results[0].status).not.toBe("forbidden");

    const booksA2 = await call("GET", "/web/notebooks", undefined, tokenA);
    expect(booksA2.json.notebooks.some((n: { id: string }) => n.id === bNote)).toBe(false);
    const booksB2 = await call("GET", "/web/notebooks", undefined, authB.json.access_token);
    expect(booksB2.json.notebooks.some((n: { id: string }) => n.id === bNote)).toBe(true);
  });
});
