import { readFileSync } from "node:fs";

export interface Config {
  host: string;
  port: number;
  databaseUrl: string;
  /** PEM of the CA that signs the Postgres server cert (Supabase: prod-ca-2021.crt). */
  databaseCaCert: string | undefined;
  deviceTokens: string[];
  s3: {
    endpoint: string | undefined;
    region: string;
    bucket: string;
    accessKeyId: string | undefined;
    secretAccessKey: string | undefined;
  };
  migrationsDir: string;
  /** Page size in device pixels assumed when rendering ink (the file does not record it). */
  page: { width: number; height: number };
}

function required(env: NodeJS.ProcessEnv, name: string): string {
  const value = env[name];
  if (!value) {
    throw new Error(`${name} is required`);
  }
  return value;
}

/** Accepts the PEM itself (handy for host env vars) or a path to it. */
function readPem(value: string | undefined): string | undefined {
  if (!value) {
    return undefined;
  }
  return value.includes("-----BEGIN") ? value.replace(/\\n/g, "\n") : readFileSync(value, "utf8");
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const deviceTokens = required(env, "DEVICE_TOKENS")
    .split(",")
    .map((t) => t.trim())
    .filter((t) => t.length > 0);
  if (deviceTokens.length === 0) {
    throw new Error("DEVICE_TOKENS must list at least one token");
  }
  return {
    host: env.HOST ?? "0.0.0.0",
    port: Number(env.PORT ?? 8787),
    databaseUrl: required(env, "DATABASE_URL"),
    databaseCaCert: readPem(env.DATABASE_CA_CERT),
    deviceTokens,
    s3: {
      endpoint: env.S3_ENDPOINT || undefined,
      region: env.S3_REGION ?? "us-east-1",
      bucket: required(env, "S3_BUCKET"),
      accessKeyId: env.S3_ACCESS_KEY || env.S3_ACCESS_KEY_ID || undefined,
      secretAccessKey: env.S3_SECRET_KEY || env.S3_SECRET_ACCESS_KEY || undefined,
    },
    migrationsDir: env.MIGRATIONS_DIR ?? new URL("../migrations", import.meta.url).pathname,
    page: {
      width: Number(env.PAGE_WIDTH ?? 1264),
      height: Number(env.PAGE_HEIGHT ?? 1680),
    },
  };
}
