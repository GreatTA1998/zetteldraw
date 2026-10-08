import { HeadBucketCommand, S3Client } from "@aws-sdk/client-s3";
import { describe, expect, it } from "vitest";
import { loadConfig } from "../../src/config.js";
import { S3InkStorage, s3ClientOptions } from "../../src/storage.js";

function env(extra: Record<string, string> = {}): NodeJS.ProcessEnv {
  return {
    DATABASE_URL: "postgres://local/zetteldraw",
    DEVICE_TOKENS: "device-token",
    S3_BUCKET: "zetteldraw-ink",
    S3_ENDPOINT: "https://example.supabase.co/storage/v1/s3",
    S3_REGION: "ap-northeast-1",
    ...extra,
  };
}

describe("S3 credentials", () => {
  it("uses the key names Render already sets and never the AWS default chain", async () => {
    const cfg = loadConfig(
      env({
        S3_ACCESS_KEY_ID: "render-key-id",
        S3_SECRET_ACCESS_KEY: "render-secret",
      }),
    );
    expect(cfg.s3.accessKeyId).toBe("render-key-id");
    expect(cfg.s3.secretAccessKey).toBe("render-secret");

    const client = new S3Client(s3ClientOptions(cfg.s3));
    try {
      const resolved = await client.config.credentials();
      expect(resolved.accessKeyId).toBe("render-key-id");
      expect(resolved.secretAccessKey).toBe("render-secret");
    } finally {
      client.destroy();
    }
  });

  it("still accepts the compose key names", () => {
    const cfg = loadConfig(
      env({
        S3_ACCESS_KEY: "compose-key",
        S3_SECRET_KEY: "compose-secret",
        S3_ACCESS_KEY_ID: "render-key-id",
        S3_SECRET_ACCESS_KEY: "render-secret",
      }),
    );
    expect(cfg.s3.accessKeyId).toBe("compose-key");
    expect(cfg.s3.secretAccessKey).toBe("compose-secret");
  });

  it("refuses to construct a client when neither name pair is set", () => {
    const cfg = loadConfig(env());
    expect(cfg.s3.accessKeyId).toBeUndefined();
    expect(() => s3ClientOptions(cfg.s3)).toThrow(/S3_ACCESS_KEY_ID/);
    expect(() => S3InkStorage.fromConfig(cfg.s3)).toThrow(/S3_ACCESS_KEY_ID/);
  });

  it("does not create the bucket when credential loading fails", async () => {
    const calls: string[] = [];
    const err = Object.assign(new Error("Could not load credentials from any providers"), {
      name: "CredentialsProviderError",
      tryNextLink: false,
    });
    const storage = new S3InkStorage(
      {
        async send(command: unknown) {
          calls.push(command instanceof HeadBucketCommand ? "head" : "other");
          throw err;
        },
      } as unknown as S3Client,
      "zetteldraw-ink",
    );
    await expect(storage.ensureBucket()).rejects.toMatchObject({ name: "CredentialsProviderError" });
    expect(calls).toEqual(["head"]);
  });

  it("creates the bucket only when it is missing", async () => {
    const calls: string[] = [];
    const missing = Object.assign(new Error("Not Found"), {
      name: "NotFound",
      $metadata: { httpStatusCode: 404 },
    });
    const storage = new S3InkStorage(
      {
        async send(command: unknown) {
          calls.push(command instanceof HeadBucketCommand ? "head" : command?.constructor?.name ?? "other");
          if (command instanceof HeadBucketCommand) {
            throw missing;
          }
          return {};
        },
      } as unknown as S3Client,
      "zetteldraw-ink",
    );
    await storage.ensureBucket();
    expect(calls).toEqual(["head", "CreateBucketCommand"]);
  });
});
