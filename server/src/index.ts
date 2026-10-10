import { existsSync } from "node:fs";
import { migrateAccounts } from "./accounts.js";
import { buildApp } from "./app.js";
import { AuthService } from "./auth.js";
import { loadConfig } from "./config.js";
import { createPool, migrate } from "./db.js";
import { S3InkStorage } from "./storage.js";
import { Thumbnailer } from "./thumbs.js";

if (existsSync(".env")) {
  process.loadEnvFile(".env");
}

const config = loadConfig();
const pool = createPool(config.databaseUrl, config.databaseCaCert);
const schemaVersion = await migrate(pool, config.migrationsDir);
await migrateAccounts(pool, config.accountMigrationsDir);
const storage = S3InkStorage.fromConfig(config.s3);
await storage.ensureBucket();

const auth = new AuthService({
  deviceTokens: config.deviceTokens,
  googleClientIds: config.googleClientIds,
  sessionSecret: config.sessionSecret,
  allowTestGoogleTokens: config.allowTestGoogleTokens,
  rejectDeviceTokensAfterClaim: config.rejectDeviceTokensAfterClaim,
});

let app: ReturnType<typeof buildApp> | undefined;
const thumbs = new Thumbnailer(storage, config.page, (err, inkHash) =>
  app?.log.error({ err, inkHash }, "thumbnail render failed"),
);
app = buildApp({
  pool,
  storage,
  schemaVersion,
  auth,
  sessionSecret: config.sessionSecret,
  thumbs,
});
await app.listen({ host: config.host, port: config.port });
app.log.info(
  {
    schemaVersion,
    googleAuth: auth.googleEnabled(),
    googleClients: config.googleClientIds.length,
  },
  "zetteldraw sync ready",
);

for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.on(signal, async () => {
    await app.close();
    await pool.end();
    process.exit(0);
  });
}
