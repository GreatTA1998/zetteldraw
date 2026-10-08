export interface Config {
  host: string;
  port: number;
  databaseUrl: string;
  deviceTokens: string[];
  s3: {
    endpoint: string | undefined;
    region: string;
    bucket: string;
    accessKeyId: string | undefined;
    secretAccessKey: string | undefined;
  };
  migrationsDir: string;
}

function required(env: NodeJS.ProcessEnv, name: string): string {
  const value = env[name]?.trim();
  if (!value) {
    throw new Error(`${name} is required`);
  }
  return value;
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
    deviceTokens,
    s3: {
      endpoint: env.S3_ENDPOINT || undefined,
      region: env.S3_REGION ?? "us-east-1",
      bucket: required(env, "S3_BUCKET"),
      accessKeyId: firstSet(env, ["S3_ACCESS_KEY", "S3_ACCESS_KEY_ID"]),
      secretAccessKey: firstSet(env, ["S3_SECRET_KEY", "S3_SECRET_ACCESS_KEY"]),
    },
    migrationsDir: env.MIGRATIONS_DIR ?? new URL("../migrations", import.meta.url).pathname,
  };
}
