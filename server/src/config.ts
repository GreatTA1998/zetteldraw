import { readFileSync } from "node:fs";
import path from "node:path";

export interface Config {
  host: string;
  port: number;
  databaseUrl: string;
  /** PEM of the CA that signs the Postgres server cert (Supabase: prod-ca-2021.crt). */
  databaseCaCert: string | undefined;
  deviceTokens: string[];
  /** Comma-separated Google OAuth client IDs (Android + Web) for ID token audience. */
  googleClientIds: string[];
  /** HMAC secret for zd1.* access tokens. Required when Google client IDs are set. */
  sessionSecret: string | undefined;
  allowTestGoogleTokens: boolean;
  /** Reject shared DEVICE_TOKENS once the library has been claimed (default true). */
  rejectDeviceTokensAfterClaim: boolean;
  s3: {
    endpoint: string | undefined;
    region: string;
    bucket: string;
    accessKeyId: string | undefined;
    secretAccessKey: string | undefined;
  };
  migrationsDir: string;
  accountMigrationsDir: string;
  /** Page size in device pixels assumed when rendering ink (the file does not record it). */
  page: { width: number; height: number };
}

function required(env: NodeJS.ProcessEnv, name: string): string {
  const value = env[name]?.trim();
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

/** First non-empty value. Older compose names are listed before the names Render already sets. */
function firstSet(env: NodeJS.ProcessEnv, names: readonly string[]): string | undefined {
  for (const name of names) {
    const value = env[name]?.trim();
    if (value) {
      return value;
    }
  }
  return undefined;
}

function bool(env: NodeJS.ProcessEnv, name: string, fallback: boolean): boolean {
  const raw = env[name]?.trim().toLowerCase();
  if (raw === undefined || raw === "") {
    return fallback;
  }
  return raw === "1" || raw === "true" || raw === "yes";
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const deviceTokens = required(env, "DEVICE_TOKENS")
    .split(",")
    .map((t) => t.trim())
    .filter((t) => t.length > 0);
  if (deviceTokens.length === 0) {
    throw new Error("DEVICE_TOKENS must list at least one token");
  }
  const googleClientIds = (env.GOOGLE_CLIENT_IDS ?? "")
    .split(",")
    .map((t) => t.trim())
    .filter((t) => t.length > 0);
  const sessionSecret = env.SESSION_SECRET?.trim() || undefined;
  if (googleClientIds.length > 0 && !sessionSecret) {
    throw new Error("SESSION_SECRET is required when GOOGLE_CLIENT_IDS is set");
  }
  const defaultMigrations = new URL("../migrations", import.meta.url).pathname;
  const defaultAccounts = new URL("../account-migrations", import.meta.url).pathname;
  return {
    host: env.HOST ?? "0.0.0.0",
    port: Number(env.PORT ?? 8787),
    databaseUrl: required(env, "DATABASE_URL"),
    databaseCaCert: readPem(env.DATABASE_CA_CERT),
    deviceTokens,
    googleClientIds,
    sessionSecret,
    allowTestGoogleTokens: bool(env, "ALLOW_TEST_GOOGLE_TOKENS", false),
    rejectDeviceTokensAfterClaim: bool(env, "REJECT_DEVICE_TOKENS_AFTER_CLAIM", true),
    s3: {
      endpoint: env.S3_ENDPOINT || undefined,
      region: env.S3_REGION ?? "us-east-1",
      bucket: required(env, "S3_BUCKET"),
      accessKeyId: firstSet(env, ["S3_ACCESS_KEY", "S3_ACCESS_KEY_ID"]),
      secretAccessKey: firstSet(env, ["S3_SECRET_KEY", "S3_SECRET_ACCESS_KEY"]),
    },
    migrationsDir: env.MIGRATIONS_DIR ?? defaultMigrations,
    accountMigrationsDir: env.ACCOUNT_MIGRATIONS_DIR ?? defaultAccounts,
    page: {
      width: Number(env.PAGE_WIDTH ?? 1264),
      height: Number(env.PAGE_HEIGHT ?? 1680),
    },
  };
}

export function resolveAccountMigrationsDir(config: Config): string {
  return path.resolve(config.accountMigrationsDir);
}
